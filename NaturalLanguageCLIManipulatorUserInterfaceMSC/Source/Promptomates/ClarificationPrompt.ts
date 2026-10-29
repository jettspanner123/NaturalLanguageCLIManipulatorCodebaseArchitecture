import { createInterface } from "node:readline/promises";
import { askSelect } from "./SelectMenu.js";

export interface ClarificationAnswer {
    readonly answerText: string;
    /** -1 when the answer was free text rather than a choice selection. */
    readonly selectedChoiceIndex: number;
}

/** Empty `choices` means free-text input; otherwise an arrow/vim-key select menu. */
export async function askClarification(question: string, choices: readonly string[]): Promise<ClarificationAnswer> {
    console.log(`\n${question}\n`);

    if (choices.length === 0) {
        const rl = createInterface({ input: process.stdin, output: process.stdout });
        const answerText = await rl.question("> ");
        rl.close();
        return { answerText, selectedChoiceIndex: -1 };
    }

    const selectedChoiceIndex = await askSelect(
        "Choose an option (arrow keys or j/k, Enter to confirm):",
        choices.map((label) => ({ label }))
    );
    return { answerText: choices[selectedChoiceIndex] ?? "", selectedChoiceIndex };
}
