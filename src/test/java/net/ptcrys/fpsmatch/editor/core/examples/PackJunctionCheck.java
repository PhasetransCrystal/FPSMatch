package net.ptcrys.fpsmatch.editor.core.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshotException;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;

/** Runs against PowerShell-created, disposable Windows junction fixtures. */
public final class PackJunctionCheck {
    private PackJunctionCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) { throw new IllegalArgumentException("FIXTURE OUTPUT"); }
        Path fixture = Path.of(args[0]);
        Path output = Path.of(args[1]);
        for (String name : new String[]{"root-link", "ancestor-link/source", "nested"}) {
            Path target = output.resolve(name.replace('/', '-') + ".edpack");
            try {
                PackSnapshots.captureDirectory(fixture.resolve(name), target, () -> false);
                throw new IllegalStateException("junction source was accepted: " + name);
            } catch (PackSnapshotException error) {
                if (error.code() != PackSnapshotException.Code.INVALID_SOURCE) { throw error; }
            }
            if (Files.exists(target)) { throw new IllegalStateException("failed capture published"); }
        }
        try (var children = Files.list(output)) {
            if (children.findAny().isPresent()) { throw new IllegalStateException("temporary residue"); }
        }
        System.out.println("WINDOWS_JUNCTION_REJECTION_PASS root/ancestor/nested; no output or residue");
    }
}
