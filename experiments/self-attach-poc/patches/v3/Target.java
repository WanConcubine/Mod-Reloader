public final class Target {
    public static String hello() {
        return "V3";
    }

    public static int add(int a, int b) {
        return a + b;
    }

    // 故意新增一个方法：用来触发 JVM 的 "schema change" 拒绝
    public static String brandNewMethod() {
        return "new";
    }
}
