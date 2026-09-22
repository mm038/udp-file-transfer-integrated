package nettransfer.control.command;

import java.util.List;
import java.util.Objects;

/** API-neutral interpreter output. Plain clarification/unsupported text cannot execute anything. */
public sealed interface CommandProposal {
    record ToolCall(String name, String argumentsJson) {
        public ToolCall {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(argumentsJson, "argumentsJson");
        }
    }

    /** The dispatcher checks the entire batch before executing any call. */
    record Calls(List<ToolCall> calls) implements CommandProposal {
        public Calls {
            calls = List.copyOf(calls);
        }
    }

    record Clarification(String question) implements CommandProposal {
        public Clarification {
            Objects.requireNonNull(question, "question");
        }
    }

    record Unsupported(String reason) implements CommandProposal {
        public Unsupported {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
