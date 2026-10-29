import { askSelect } from "./SelectMenu.js";

export const ACCESS_DECISION_LABELS = [
    "Allow once",
    "Always allow this folder",
    "Always allow this folder and subfolders",
    "Deny",
] as const;

/** Returns the index into ACCESS_DECISION_LABELS the user picked. */
export async function askAccessRequest(directory: string, reason: string): Promise<number> {
    console.log(`\nAmy needs access to:\n  ${directory}\n`);
    console.log(`  ${reason}\n`);
    return askSelect(
        "Choose an option (arrow keys or j/k, Enter to confirm):",
        ACCESS_DECISION_LABELS.map((label) => ({ label }))
    );
}
