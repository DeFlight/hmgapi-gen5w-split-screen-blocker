package hmgapi.hook;
/** Compile-only stand-in for HMG-API's hook API; never packaged. */
public abstract class MethodHook {
    protected void beforeHookedMethod(Param p) throws Throwable {}
    protected void afterHookedMethod(Param p) throws Throwable {}
    public static final class Param {
        public final Object thisObject = null;
        public final Object[] args = null;
        public void setResult(Object result) {}
    }
}
