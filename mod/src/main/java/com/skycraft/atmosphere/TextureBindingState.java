package com.skycraft.atmosphere;

/** State-preserving texture-unit borrowing, testable independently of Minecraft or a GPU. */
public final class TextureBindingState implements AutoCloseable {
    public static final int ZERO=0x84c0;
    public interface Bindings {
        int actualActive();
        int cachedActive();
        int actualBinding();
        void active(int unit);
        void bind(int texture);
    }
    private final Bindings bindings;
    private final int active,binding;
    private final boolean resynchronized;

    public TextureBindingState(Bindings bindings) {
        this.bindings=bindings;
        active=bindings.actualActive();
        resynchronized=active!=bindings.cachedActive();
        zero();
        binding=bindings.actualBinding();
        bind(binding);
    }

    private void zero() { bindings.active(ZERO+1);bindings.active(ZERO); }
    private void bind(int texture) { bindings.bind(0);bindings.bind(texture); }
    public boolean resynchronized() { return resynchronized; }

    @Override public void close() {
        zero();bind(binding);bindings.active(active);
    }
}
