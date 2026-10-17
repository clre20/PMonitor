package clre20.pMonitor.nms;

/** 存取伺服器內部（NMS）失敗。 */
public class NmsException extends RuntimeException {

    public NmsException(String message) {
        super(message);
    }

    public NmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
