const KEY_UP = String.fromCharCode(27) + "[A";
const KEY_DOWN = String.fromCharCode(27) + "[B";
const KEY_CTRL_C = String.fromCharCode(3);
const CLEAR_LINE_UP = String.fromCharCode(27) + "[1A" + String.fromCharCode(27) + "[2K";

export interface SelectMenuOption {
    readonly label: string;
}

/**
 * Renders an interactive single-select menu and returns the index of the
 * chosen option. Supports arrow keys (Up/Down) and vim keys (j/k) to move
 * the cursor, Enter to confirm. Used by both the Access Request prompt
 * (fixed options) and the Clarification prompt (dynamic choices).
 */
export function askSelect(prompt: string, options: readonly SelectMenuOption[]): Promise<number> {
    return new Promise((resolve) => {
        let selectedIndex = 0;

        const render = (): void => {
            process.stdout.write(`${prompt}\n`);
            for (const [index, option] of options.entries()) {
                const pointer = index === selectedIndex ? ">" : " ";
                process.stdout.write(`  ${pointer} ${option.label}\n`);
            }
        };

        const clearRender = (): void => {
            for (let line = 0; line < options.length + 1; line++) {
                process.stdout.write(CLEAR_LINE_UP);
            }
        };

        const stdin = process.stdin;
        const wasRaw = stdin.isTTY ? stdin.isRaw : false;
        if (stdin.isTTY) {
            stdin.setRawMode(true);
        }
        stdin.resume();
        stdin.setEncoding("utf8");

        render();

        const finish = (): void => {
            stdin.removeListener("data", onData);
            if (stdin.isTTY) {
                stdin.setRawMode(wasRaw);
            }
            stdin.pause();
            resolve(selectedIndex);
        };

        const onData = (key: string): void => {
            if (key === KEY_CTRL_C) {
                process.exit(1);
            } else if (key === "\r" || key === "\n") {
                clearRender();
                process.stdout.write(`  > ${options[selectedIndex]?.label}\n\n`);
                finish();
            } else if (key === KEY_UP || key === "k") {
                clearRender();
                selectedIndex = (selectedIndex - 1 + options.length) % options.length;
                render();
            } else if (key === KEY_DOWN || key === "j") {
                clearRender();
                selectedIndex = (selectedIndex + 1) % options.length;
                render();
            }
        };

        stdin.on("data", onData);
    });
}
