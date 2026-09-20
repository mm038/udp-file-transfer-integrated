package nettransfer.control;

/** UNCONFIRMED includes a missing final acknowledgement; it is not success. */
public enum IntegrityStatus {
    VERIFIED, FAILED, UNCONFIRMED
}
