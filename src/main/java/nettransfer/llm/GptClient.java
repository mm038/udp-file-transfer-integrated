package nettransfer.llm;

import nettransfer.control.command.CommandProposal;

/** Interprets words only. Implementations have no TransferService and cannot execute commands. */
@FunctionalInterface
public interface GptClient {
    CommandProposal interpret(InterpretationRequest request);
}
