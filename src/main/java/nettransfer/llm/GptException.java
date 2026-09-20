package nettransfer.llm;

/** Fixed, credential-free messages; never retain an HTTP body, header, or exception cause. */
public final class GptException extends RuntimeException {
    public enum Code {
        MISSING_CREDENTIALS("OPENAI_API_KEY is missing."),
        INVALID_CONFIGURATION("GPT configuration is invalid; check model and timeout settings."),
        AUTHENTICATION("The API rejected the supplied credentials or permissions."),
        RATE_LIMIT("The API rate limit or quota prevented this request."),
        TIMEOUT("The API request exceeded its deadline."),
        TRANSPORT("The API could not be reached or its response was interrupted."),
        REFUSED("The model declined this request."),
        INCOMPLETE("The model response was incomplete."),
        INVALID_RESPONSE("The API returned an invalid or unexpected response."),
        UNAVAILABLE("GPT is unavailable.");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }

    private final Code code;

    public GptException(Code code) {
        super(java.util.Objects.requireNonNull(code, "code").message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
