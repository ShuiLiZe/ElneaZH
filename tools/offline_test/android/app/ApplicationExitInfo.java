package android.app;

/** 极小 stub。 */
public class ApplicationExitInfo {
    public static final int REASON_EXIT_SELF = 1, REASON_SIGNALED = 2, REASON_LOW_MEMORY = 3, REASON_CRASH = 4, REASON_CRASH_NATIVE = 5, REASON_ANR = 6, REASON_USER_REQUESTED = 10, REASON_USER_STOPPED = 11, REASON_OTHER = 13;
    public int getReason() { return REASON_OTHER; }
    public int getStatus() { return 0; }
    public long getTimestamp() { return 0; }
    public String getDescription() { return ""; }
}
