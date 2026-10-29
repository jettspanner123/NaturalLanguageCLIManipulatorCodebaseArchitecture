import type { Socket } from "node:net";

/**
 * Reads length-prefixed frames (4-byte big-endian length + payload) off a
 * loopback socket's byte stream. See AmyIpc.proto for why framing is
 * needed on top of raw protobuf bytes.
 */
export class MessageFrameReader {
    private buffer: Buffer = Buffer.alloc(0);
    private readonly pendingFrames: Buffer[] = [];
    private readonly waitingResolvers: Array<(frame: Buffer | null) => void> = [];
    private closed = false;

    constructor(socket: Socket) {
        socket.on("data", (chunk: Buffer) => {
            this.buffer = Buffer.concat([this.buffer, chunk]);
            this.drain();
        });
        socket.on("close", () => {
            this.closed = true;
            this.drain();
        });
    }

    private drain(): void {
        while (this.buffer.length >= 4) {
            const frameLength = this.buffer.readUInt32BE(0);
            if (this.buffer.length < 4 + frameLength) {
                break;
            }
            this.pendingFrames.push(Buffer.from(this.buffer.subarray(4, 4 + frameLength)));
            this.buffer = this.buffer.subarray(4 + frameLength);
        }

        while (this.waitingResolvers.length > 0 && (this.pendingFrames.length > 0 || this.closed)) {
            const resolve = this.waitingResolvers.shift();
            resolve?.(this.pendingFrames.shift() ?? null);
        }
    }

    /** Resolves with the next full frame, or null once the socket has closed. */
    readFrame(): Promise<Buffer | null> {
        return new Promise((resolve) => {
            this.waitingResolvers.push(resolve);
            this.drain();
        });
    }
}

/** Writes one length-prefixed frame (4-byte big-endian length + payload) to the socket. */
export function writeFrame(socket: Socket, bytes: Uint8Array): void {
    const lengthPrefix = Buffer.alloc(4);
    lengthPrefix.writeUInt32BE(bytes.length, 0);
    socket.write(lengthPrefix);
    socket.write(bytes);
}
