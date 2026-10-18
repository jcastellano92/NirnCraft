package com.skycraft.client.autotest;

import java.util.Collection;
import java.util.regex.Pattern;

/** Capture-only interpretation of the installed Embeddium renderer's public diagnostics. */
final class TerrainMeshReadiness {
    private static final Pattern BUILDER = Pattern.compile(
            "Chunk Builder: Permits=(\\d+) \\| Busy=(\\d+) \\| Total=(\\d+)");
    private static final Pattern QUEUES = Pattern.compile(
            "Chunk Queues: U=(\\d+) \\(P0=(\\d+) \\| P1=(\\d+) \\| P2=(\\d+)\\)");

    private static final Pattern SODIUM_BUILDER=Pattern.compile("Chunk Builder: Schd=(\\d+) \\| Busy=(\\d+) \\([0-9]+%\\) \\| Total=(\\d+)");
    private static final Pattern SODIUM_QUEUES=Pattern.compile("Tasks: N0=(\\d+) \\| N1=(\\d+) \\| Def=(\\d+), Recv=(\\d+)");

    record Counters(int scheduled, int busy, int threads, int uploads, int important, int rebuild, int initial) {
        boolean idle(boolean queueEmpty) {
            return queueEmpty && scheduled == 0 && busy == 0 && uploads == 0
                    && important == 0 && rebuild == 0 && initial == 0;
        }

        String describe() {
            return "builder=" + scheduled + "/" + busy + "/" + threads + " uploads=" + uploads
                    + " rebuilds=" + important + "/" + rebuild + "/" + initial;
        }
    }

    static Counters parse(Collection<?> diagnostics) {
        if (diagnostics == null) throw unsupported("missing diagnostics");
        int[] builder = null, queues = null;
        for (Object entry : diagnostics) {
            if (!(entry instanceof String text)) throw unsupported("non-text diagnostic");
            text = text.trim();
            var b = BUILDER.matcher(text);
            var q = QUEUES.matcher(text);
            var sb=SODIUM_BUILDER.matcher(text);var sq=SODIUM_QUEUES.matcher(text);
            if(sb.matches()){
                if(builder!=null)throw unsupported("duplicate builder counters");
                builder=new int[]{count(sb.group(1)),count(sb.group(2)),count(sb.group(3))};
            }else if(sq.matches()){
                if(queues!=null)throw unsupported("duplicate queue counters");
                queues=new int[]{count(sq.group(4)),count(sq.group(1)),count(sq.group(2)),count(sq.group(3))};
            }else if (b.matches()) {
                if (builder != null) throw unsupported("duplicate builder counters");
                builder = new int[]{count(b.group(1)), count(b.group(2)), count(b.group(3))};
            } else if (q.matches()) {
                if (queues != null) throw unsupported("duplicate queue counters");
                queues = new int[]{count(q.group(1)), count(q.group(2)), count(q.group(3)), count(q.group(4))};
            } else if (text.startsWith("Chunk Builder:") || text.startsWith("Chunk Queues:") || text.startsWith("Tasks:")) {
                throw unsupported("unrecognized counter format: " + text);
            }
        }
        if (builder == null || queues == null) throw unsupported("missing builder/queue counters");
        if (builder[1] > builder[2]) throw unsupported("busy count exceeds worker count");
        return new Counters(builder[0], builder[1], builder[2], queues[0], queues[1], queues[2], queues[3]);
    }

    private static int count(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw unsupported("counter outside integer range"); }
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException("Unsupported terrain renderer capture readiness diagnostics: " + reason);
    }

    /** A client can run several catch-up ticks without rendering. Only new completed frames count here. */
    static final class Frames {
        private int frame, visible, stable;
        private boolean seen;

        void reset() { seen = false; stable = 0; }

        boolean observe(int renderedFrame, int visibleSections, boolean idle) {
            if (visibleSections < 0) throw unsupported("negative visible-section count");
            if (!seen || !idle || visibleSections != visible) {
                seen = true; frame = renderedFrame; visible = visibleSections; stable = 0;
                return false;
            }
            if (renderedFrame != frame) { frame = renderedFrame; stable = Math.min(2, stable + 1); }
            return stable >= 2;
        }

        String describe() { return "visible=" + visible + " stableRenderedFrames=" + stable + "/2"; }
    }

    /** Only a completed MAIN world pass can provide evidence; ticks and shadow passes cannot advance it. */
    static final class MainFrames {
        private final Frames frames=new Frames();
        private int observedFrame;
        private boolean seen,ready;
        void reset() { frames.reset();seen=ready=false; }
        boolean complete(int frame,int visible,boolean idle,boolean shadow) {
            if(shadow) return false;
            seen=true;observedFrame=frame;ready=frames.observe(frame,visible,idle);
            return ready;
        }
        boolean readyAt(int frame) { return readyAt(frame,false); }
        boolean readyAt(int frame,boolean rendering) {
            // During a newly started render, its main pass has not completed yet. Only its immediate predecessor
            // can witness a previously stable view. Once this render ends, its own main witness is mandatory.
            return seen && ready && (observedFrame==frame || (rendering && frame-observedFrame==1));
        }
        String age(int frame) { return seen?Integer.toString(frame-observedFrame):"none"; }
        String describe() { return frames.describe()+" mainFrame="+(seen?observedFrame:"none"); }
    }

    static double yawDelta(float previous,float current) { return Math.IEEEremainder((double)current-previous,360); }
    static boolean sameView(double positionDeltaSquared,float previousYaw,float previousPitch,float yaw,float pitch) {
        // Sub-millimetre position and 0.001-degree angular tolerance cover float roundoff, including +/-360 yaw.
        // Compare against the completed view, never accumulate tolerated deltas into an unchecked moving anchor.
        return Double.isFinite(positionDeltaSquared) && positionDeltaSquared<1e-8
                && Math.abs(yawDelta(previousYaw,yaw))<=0.001 && Math.abs((double)pitch-previousPitch)<=0.001;
    }
}
