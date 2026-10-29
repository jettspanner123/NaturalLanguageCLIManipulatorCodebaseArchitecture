import * as net from "node:net";
import { CoreToUserInterface, UserInterfaceToCore } from "./IPC/Generated/AmyIpc.js";
import { MessageFrameReader, writeFrame } from "./IPC/MessageFraming.js";
import { askConfirmation } from "./Promptomates/ConfirmationPrompt.js";

async function runLoop(socket: net.Socket, reader: MessageFrameReader): Promise<void> {
    for (;;) {
        const frame = await reader.readFrame();
        if (frame === null) {
            break;
        }

        const message = CoreToUserInterface.fromBinary(frame);

        switch (message.payload.oneofKind) {
            case "confirmationRequest": {
                const { command, reason } = message.payload.confirmationRequest;
                const approved = await askConfirmation(command, reason);
                const response = UserInterfaceToCore.create({
                    payload: { oneofKind: "confirmationResponse", confirmationResponse: { approved } },
                });
                writeFrame(socket, UserInterfaceToCore.toBinary(response));
                break;
            }
            case "outputLine": {
                console.log(message.payload.outputLine.text);
                break;
            }
            default:
                console.error(`Amy UI process: received an unhandled message kind: ${message.payload.oneofKind}`);
        }
    }

    process.exit(0);
}

function main(): void {
    const port = Number(process.argv[2]);
    if (!Number.isInteger(port)) {
        console.error("Amy UI process: expected a port number as the first argument.");
        process.exit(1);
    }

    const socket = net.connect(port, "127.0.0.1");
    const reader = new MessageFrameReader(socket);

    socket.on("connect", () => {
        void runLoop(socket, reader);
    });

    socket.on("error", (error) => {
        console.error(`Amy UI process: socket error: ${error.message}`);
        process.exit(1);
    });
}

main();
