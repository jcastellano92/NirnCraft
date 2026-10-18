import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

/** Development-only orderly stop. Uses public APIs; never transforms game classes. */
public final class SkycraftServerStop {
    private static Path real(String path) throws Exception { return Path.of(path).toRealPath(); }
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void validate(Properties props, Path expectedJava, Path expectedRun, Path expectedControl) throws Exception {
        require("25".equals(props.getProperty("java.specification.version")), "Target must use Java 25");
        require(real(props.getProperty("java.home")).equals(expectedJava.getParent().getParent()), "Java home mismatch");
        require(real(props.getProperty("user.dir")).equals(expectedRun), "Runtime directory mismatch");
        String command = props.getProperty("sun.java.command", "");
        require(command.startsWith("net.neoforged.devlaunch.Main ") && command.contains("serverRunProgramArgs.txt"),
                "Target is not an isolated NeoForge development server");
        require(real(props.getProperty("skycraft.dev", "")).equals(expectedControl),
                "Skycraft development-control directory mismatch");
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 6, "Usage: PID EXPECTED_JAVA EXPECTED_RUNTIME EXPECTED_CONTROL AGENT_JAR RECEIPT");
        long pid = Long.parseLong(args[0]);
        Path java = real(args[1]), run = real(args[2]), control = real(args[3]), jar = real(args[4]);
        Path receipt = Path.of(args[5]).toAbsolutePath().normalize();
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("PID is not running"));
        require(real(handle.info().command().orElseThrow()).equals(java), "Process executable mismatch");
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            validate(vm.getSystemProperties(), java, run, control);
            System.out.println("Validated native development server PID " + pid + " at " + run);
            vm.loadAgent(jar.toString(), encode(java.toString()) + ":" + encode(run.toString()) + ":" + encode(control.toString()) + ":" + encode(receipt.toString()));
        } finally { vm.detach(); }
    }
    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        String[] values = args.split(":", -1);
        require(values.length == 4, "Invalid stop request");
        Path java = real(decode(values[0])), run = real(decode(values[1]));
        Path control = real(decode(values[2]));
        Path receipt = Path.of(decode(values[3]));
        validate(System.getProperties(), java, run, control);
        Class<?> hooks = null;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")) {
                hooks = type;
                break;
            }
        }
        require(hooks != null, "Native ServerLifecycleHooks is not loaded");
        Object server = hooks.getMethod("getCurrentServer").invoke(null);
        require(server != null && server.getClass().getName().equals("net.minecraft.server.dedicated.DedicatedServer"),
                "Target has no native dedicated server");
        var halt = server.getClass().getMethod("halt", boolean.class);
        Thread stop = new Thread(() -> {
            try {
                Files.writeString(receipt, "halt_requested " + Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
                halt.invoke(server, true);
                Files.writeString(receipt, "halt_completed " + Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (Throwable error) {
                Throwable cause = error instanceof InvocationTargetException wrapped ? wrapped.getCause() : error;
                try { Files.writeString(receipt, "halt_failed " + cause + System.lineSeparator(), StandardCharsets.UTF_8); }
                catch (Exception ignored) { }
                cause.printStackTrace();
            }
        }, "Skycraft orderly development-server stop");
        stop.setDaemon(false);
        stop.start();
    }
}
