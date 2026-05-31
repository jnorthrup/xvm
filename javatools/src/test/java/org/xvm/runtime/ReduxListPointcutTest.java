package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/**
 * TDD: Replace named ArrayList ctors with ReduxMutableSeries via pointcut.
 *
 * Flow:
 *   new ArrayList() at named site
 *     → rewritten to invokestatic ReduxBridge.createDefault()
 *     → returns ReduxMutableSeries<T, Series<T>>
 *     → .toSeries().toList() as unary getter
 *
 *   new ArrayList(n) at named site
 *     → rewritten to invokestatic ReduxBridge.createSized(n)
 *     → same return type
 */
public class ReduxListPointcutTest {

    // ── 1. RED: new ArrayList() at named site → replaced with ReduxBridge ──

    @org.junit.jupiter.api.Test
    public void arrayListCtor_replacedWithReduxBridge() {
        byte[] original = buildClassWithArrayListCtor("ReduxTarget1");

        // Site: REPLACE new ArrayList() in ReduxTarget1.test with bridge call
        var site = ClassfilePointcutRewriter.PointcutSite.replace(
                0x38, "ReduxTarget1.test", 0, "()V",
                "org/xvm/runtime/ReduxListBridge", "createDefault",
                MethodTypeDesc.of(ClassDesc.of("borg.trikeshed.lib.ReduxMutableSeries"))
        );

        byte[] rewritten = ClassfilePointcutRewriter.rewriteReplace(original, List.of(site));

        // Should NOT contain new ArrayList anymore
        assertFalse(containsNew(rewritten, "java/util/ArrayList"),
                "new ArrayList should be removed");
        // Should contain invokestatic ReduxListBridge.createDefault
        assertTrue(containsInvokestatic(rewritten, "org/xvm/runtime/ReduxListBridge", "createDefault"),
                "should call ReduxListBridge.createDefault");
    }

    // ── 2. RED: new ArrayList(n) at named site → replaced with ReduxBridge ──

    @org.junit.jupiter.api.Test
    public void arrayListSizedCtor_replacedWithReduxBridge() {
        byte[] original = buildClassWithArrayListSizedCtor("ReduxTarget2");

        var site = ClassfilePointcutRewriter.PointcutSite.replace(
                0x38, "ReduxTarget2.test", 0, "()V",
                "org/xvm/runtime/ReduxListBridge", "createSized",
                MethodTypeDesc.of(ClassDesc.of("borg.trikeshed.lib.ReduxMutableSeries"), ClassDesc.of("int"))
        );

        byte[] rewritten = ClassfilePointcutRewriter.rewriteReplace(original, List.of(site));

        assertFalse(containsNew(rewritten, "java/util/ArrayList"),
                "new ArrayList should be removed");
        assertTrue(containsInvokestatic(rewritten, "org/xvm/runtime/ReduxListBridge", "createSized"),
                "should call ReduxListBridge.createSized");
    }

    // ── 3. Non-named site NOT replaced ──────────────────────────────────

    @org.junit.jupiter.api.Test
    public void arrayListCtor_unnamedSite_notReplaced() {
        byte[] original = buildClassWithArrayListCtor("ReduxTarget3");

        // Site targets a DIFFERENT method — should not match
        var site = ClassfilePointcutRewriter.PointcutSite.replace(
                0x38, "OtherMethod.differentMethod", 0, "()V",
                "org/xvm/runtime/ReduxListBridge", "createDefault",
                MethodTypeDesc.of(ClassDesc.of("borg.trikeshed.lib.ReduxMutableSeries"))
        );

        byte[] rewritten = ClassfilePointcutRewriter.rewriteReplace(original, List.of(site));

        // ArrayList should still be there
        assertTrue(containsNew(rewritten, "java/util/ArrayList"),
                "non-matching site should leave ArrayList intact");
    }

    // ── 4. Bridge produces working ReduxMutableSeries ───────────────────

    @org.junit.jupiter.api.Test
    public void bridge_createsRedux() {
        var redux = ReduxListBridge.createDefault();
        assertNotNull(redux);
        assertEquals(0, redux.getA());
    }

    // ── 5. Bridge sized ────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void bridge_createsSizedRedux() {
        var redux = ReduxListBridge.createSized(64);
        assertNotNull(redux);
        assertEquals(0, redux.getA());
    }

    // ── 6. Redux add + state reify ─────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void redux_addAndReify() {
        var redux = ReduxListBridge.<String>createDefault();
        redux.add("alpha");
        redux.add("beta");

        assertEquals(2, redux.getA());
        assertEquals("alpha", redux.getB().invoke(0));
        assertEquals("beta", redux.getB().invoke(1));

        // state = CollectorReducer fold → Series of all dispatched events
        var state = redux.getState();
        var stateSeries = (borg.trikeshed.lib.Join) state;
        assertEquals(2, stateSeries.getA());
    }

    // ── 7. toSeries().toList() round-trip from Redux ───────────────────

    @org.junit.jupiter.api.Test
    public void redux_toSeries_toList() {
        var redux = ReduxListBridge.<String>createDefault();
        redux.add("x");
        redux.add("y");

        var result = ReduxListBridge.toSeriesToList(redux);
        assertEquals(2, result.size());
        assertEquals("x", result.get(0));
        assertEquals("y", result.get(1));
    }

    // ── 8. Bridge wraps existing ArrayList contents ────────────────────

    @org.junit.jupiter.api.Test
    public void bridge_wrapExistingList() {
        var list = new java.util.ArrayList<String>();
        list.add("existing");

        var redux = ReduxListBridge.wrap(list);
        assertEquals(1, redux.getA());
        assertEquals("existing", redux.getB().invoke(0));

        redux.add("added");
        var result = ReduxListBridge.toSeriesToList(redux);
        assertEquals(2, result.size());
        assertEquals("existing", result.get(0));
        assertEquals("added", result.get(1));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test class builders
    // ══════════════════════════════════════════════════════════════════════

    private static final ClassDesc ARRAYLIST_CD = ClassDesc.of("java.util.ArrayList");
    private static final MethodTypeDesc VOID_DESC = MethodTypeDesc.of(ClassDesc.of("void"));

    private static byte[] buildClassWithArrayListCtor(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", VOID_DESC,
                java.lang.reflect.AccessFlag.STATIC.mask(),
                mb -> mb.withCode(code -> {
                    code.new_(ARRAYLIST_CD);
                    code.dup();
                    code.invokespecial(ARRAYLIST_CD, "<init>",
                            MethodTypeDesc.of(ClassDesc.of("void")));
                    code.pop();
                    code.return_();
                }));
        });
    }

    private static byte[] buildClassWithArrayListSizedCtor(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", VOID_DESC,
                java.lang.reflect.AccessFlag.STATIC.mask(),
                mb -> mb.withCode(code -> {
                    code.new_(ARRAYLIST_CD);
                    code.dup();
                    code.bipush(64);
                    code.invokespecial(ARRAYLIST_CD, "<init>",
                            MethodTypeDesc.of(ClassDesc.of("void"), ClassDesc.of("int")));
                    code.pop();
                    code.return_();
                }));
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Verification helpers
    // ══════════════════════════════════════════════════════════════════════

    private static boolean containsNew(byte[] classBytes, String internalName) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) continue;
            for (CodeElement ce : mm.code().get().elementList()) {
                if (ce instanceof NewObjectInstruction noi) {
                    if (noi.className().asInternalName().equals(internalName)) return true;
                }
            }
        }
        return false;
    }

    private static boolean containsInvokestatic(byte[] classBytes, String owner, String name) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) continue;
            for (CodeElement ce : mm.code().get().elementList()) {
                if (ce instanceof InvokeInstruction inv) {
                    if (inv.owner().asInternalName().equals(owner)
                            && inv.name().stringValue().equals(name)
                            && inv.opcode() == Opcode.INVOKESTATIC) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
