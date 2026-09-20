package nettransfer.llm;

import nettransfer.control.command.CommandProposal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Scripted offline interpreter; consumes supplied proposals without HTTP or credentials. */
public final class StubGptClient implements GptClient {
    private final ArrayDeque<CommandProposal> proposals;
    private final List<InterpretationRequest> requests = new ArrayList<>();

    public StubGptClient(List<CommandProposal> proposals) {
        this.proposals = new ArrayDeque<>(List.copyOf(proposals));
    }

    @Override
    public synchronized CommandProposal interpret(InterpretationRequest request) {
        requests.add(Objects.requireNonNull(request, "request"));
        if (proposals.isEmpty()) {
            throw new GptException(GptException.Code.UNAVAILABLE);
        }
        return proposals.removeFirst();
    }

    public synchronized List<InterpretationRequest> requests() {
        return List.copyOf(requests);
    }
}
