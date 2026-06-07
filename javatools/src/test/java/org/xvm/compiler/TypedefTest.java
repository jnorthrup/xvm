package org.xvm.compiler;

import org.junit.jupiter.api.Test;
import org.xvm.asm.ErrorList;
import org.xvm.tool.Compiler;
import org.xvm.tool.Launcher;
import org.xvm.tool.Launcher.LauncherException;
import org.xvm.tool.LauncherOptions.CompilerOptions;

import java.io.File;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test compile for typedef-related cases, specifically verifying parameterized mixin resolution.
 */
public class TypedefTest {
    @Test
    public void testMixin() {
        // Find the project root
        File dirRoot = new File(".").getAbsoluteFile();
        while (dirRoot != null && !new File(dirRoot, "lib_ecstasy").exists()) {
            dirRoot = dirRoot.getParentFile();
        }
        if (dirRoot == null) {
            throw new IllegalStateException("Could not find project root containing lib_ecstasy");
        }

        File fileSrc = new File(dirRoot, "javatools/src/test/x/typedefs/test_mixin.x");
        File dirLib = new File(dirRoot, "lib_ecstasy/build/xtc/main/lib");
        File dirOut = new File(dirRoot, "javatools/build/test-typedef");
        dirOut.mkdirs();

        CompilerOptions opts = CompilerOptions.builder()
                .addModulePath(dirLib)
                .setOutputLocation(dirOut)
                .addInputFile(fileSrc)
                .forceRebuild()
                .build();

        ErrorList errlist = new ErrorList(20);
        Compiler compiler = new Compiler(opts, null, errlist);
        try {
            int nResult = compiler.run();
            assertEquals(0, nResult, "Compilation failed!");
        } catch (LauncherException e) {
            String sErrors = errlist.getErrors().stream()
                    .map(err -> err.toString())
                    .collect(Collectors.joining("\n"));
            fail("Compilation failed with type errors:\n" + sErrors, e);
        }
    }
}
