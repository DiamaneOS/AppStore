package android.content.pm;
// Records what a session receives, in memory.
public class PackageInstaller {
    public static final int PACKAGE_SOURCE_STORE = 2;
    public final java.util.Map<Integer, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(1);
    public int createSession(SessionParams p) {
        int id = nextId.getAndIncrement();
        sessions.put(id, new Session());
        return id;
    }
    public Session openSession(int id) throws java.io.IOException {
        Session s = sessions.get(id);
        if (s == null || s.abandoned) throw new SecurityException("No session " + id);
        return s;
    }
    public void abandonSession(int id) { Session s = sessions.get(id); if (s != null) s.abandoned = true; }

    public static class Session implements java.io.Closeable {
        public final java.util.Map<String, java.io.ByteArrayOutputStream> files = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile boolean committed, abandoned;
        public java.io.OutputStream openWrite(String name, long offset, long length) throws java.io.IOException {
            if (committed || abandoned) throw new IllegalStateException("Session closed");
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            if (offset != 0) throw new java.io.IOException("Only whole files in the fixture");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            files.put(name, out);
            return out;
        }
        public void commit(android.content.IntentSender s) { if (abandoned) throw new SecurityException("abandoned"); committed = true; }
        public void addChildSessionId(int id) {}
        public void close() {}
    }

    public static class SessionParams {
        public static final int MODE_FULL_INSTALL = 1, USER_ACTION_REQUIRED = 1, USER_ACTION_NOT_REQUIRED = 2;
        public SessionParams(int mode) {}
        public void setAppPackageName(String s) {}
        public void setAppLabel(CharSequence s) {}
        public void setRequireUserAction(int a) {}
        public void setInstallScenario(int s) {}
        public void setPackageSource(int s) {}
        public void setApplicationEnabledSettingPersistent() {}
        public void setRequestUpdateOwnership(boolean b) {}
        public void setSize(long s) {}
    }

    public static class SessionInfo { public static final int INVALID_ID = -1; public int sessionId; }
}
