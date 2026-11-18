package demo.mod;

/**
 * Compiled against the stub where {@code Target.Hidden} is public. At runtime the
 * real nested class is package-private, and no call-site rewrite can fix a type
 * reference: this is the hard boundary of the "implement AT ourselves" idea.
 */
public final class BadCode {
    private BadCode() {
    }

    public static int use() {
        demo.Target.Hidden hidden = new demo.Target.Hidden();
        hidden.v = 3;
        return hidden.v;
    }
}
