const CTRL_C = String.fromCharCode(3);

/**
 * Asks the user to confirm or decline a flagged command. Reads a single
 * raw y/n keypress (no Enter needed) rather than a full readline, so the
 * prompt feels immediate. See CONTEXT.md: Confirmation, Command Echo.
 */
export function askConfirmation(command: string, reason: string): Promise<boolean> {
    return new Promise((resolve) => {
        console.log(`\nAmy wants to run:\n  ${command}\n`);
        console.log(`  ${reason}\n`);
        process.stdout.write("Proceed? [y/n] ");

        const stdin = process.stdin;
        const wasRaw = stdin.isTTY ? stdin.isRaw : false;
        if (stdin.isTTY) {
            stdin.setRawMode(true);
        }
        stdin.resume();
        stdin.setEncoding("utf8");

        const finish = (approved: boolean): void => {
            stdin.removeListener("data", onData);
            if (stdin.isTTY) {
                stdin.setRawMode(wasRaw);
            }
            stdin.pause();
            console.log(approved ? "Confirmed (y)\n" : "Declined (n)\n");
            resolve(approved);
        };

        const onData = (key: string): void => {
            if (key === "y" || key === "Y") {
                finish(true);
            } else if (key === "n" || key === "N" || key === CTRL_C) {
                finish(false);
            }
        };

        stdin.on("data", onData);
    });
}
