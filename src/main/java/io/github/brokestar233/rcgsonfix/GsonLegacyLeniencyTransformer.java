package io.github.brokestar233.rcgsonfix;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.jar.Manifest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Restores the legacy (gson &le; 2.10) lenient behavior of {@code Gson.fromJson} on modern gson,
 * where the runtime returns whatever the registered adapter produced instead of throwing a
 * {@code ClassCastException: Type adapter '...' returned wrong type}.
 *
 * <p>1.7.10-era mods were built against gson 2.2.4, whose {@code fromJson} had no
 * adapter-result type check. gson 2.11.0 added a strict {@code expectedType.isInstance(result)}
 * check that throws on mismatch. Packs that ship a modern gson alongside modern Java runtimes
 * (e.g. RecurrentComplex 0.9.7.x with gson 2.14.0, whose JSON-to-NBT adapter design routinely
 * returns a different concrete NBT type than the declared one) then fail to load their bundled
 * resources with {@code Error loading resource} spam.</p>
 *
 * <p>The patch is a single, structural edit in {@code com.google.gson.Gson}: it finds the
 * {@code ClassCastException("... returned wrong type ...")} throw block, and rewrites the
 * conditional jump guarding it into {@code POP; GOTO} - the check is skipped entirely, exactly
 * like gson 2.2.x did. The exception-construction code stays in place as unreachable code, no
 * branch targets or stack map frames are touched, and the method signature is unchanged.</p>
 */
public class GsonLegacyLeniencyTransformer implements RfbClassTransformer {
    public static final Logger LOGGER = LogManager.getLogger("RC-Gson-Fix");

    private static final String GSON_CLASS = "com.google.gson.Gson";
    private static final String ANCHOR_SUBSTRING = "returned wrong type";

    @Override
    public @NotNull String id() {
        return "rc-gson-legacy-lenient";
    }

    @Override
    public boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        // Cheap name check only - avoid triggering the lazy ASM parse for other classes
        return className.equals(GSON_CLASS) && classNode.isPresent();
    }

    @Override
    public boolean transformClassIfNeeded(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        try {
            final org.objectweb.asm.tree.ClassNode node = classNode.getNode();
            if (node == null || node.methods == null) {
                return false;
            }
            boolean changed = false;
            for (MethodNode method : node.methods) {
                changed |= neutralizeWrongTypeCheck(method);
            }
            if (!changed) {
                // gson <= 2.10 never had the check - nothing to do, stay silent
                return false;
            }
            classNode.computeMaxs();
            LOGGER.info("Restored legacy lenient Gson.fromJson adapter handling (gson "
                    + node.name + " copy in " + context.name() + ")");
            return true;
        } catch (final Throwable t) {
            LOGGER.error("Failed to patch " + className + ", leaving the class untouched", t);
            return false;
        }
    }

    /**
     * Finds the {@code if (result != null && !expected.isInstance(result)) throw CCE("...returned
     * wrong type...")} block and replaces the second conditional jump (the one guarding the throw,
     * i.e. whose target label sits after the athrow) with {@code POP; GOTO target}. Stack shape at
     * the target is identical to the original fall-through/skip path, so no frames change.
     */
    private static boolean neutralizeWrongTypeCheck(MethodNode method) {
        if (method.instructions == null) {
            return false;
        }
        // Labels that are actual control-flow targets: jump targets and exception handler
        // boundaries. Debug-info labels (line numbers, local variable ranges) are NOT in this
        // set - encountering those inside the throw block is fine.
        final java.util.Set<AbstractInsnNode> branchTargets = new java.util.HashSet<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode) {
                branchTargets.add(((JumpInsnNode) insn).label);
            } else if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) {
                branchTargets.addAll(((org.objectweb.asm.tree.LookupSwitchInsnNode) insn).labels);
                branchTargets.add(((org.objectweb.asm.tree.LookupSwitchInsnNode) insn).dflt);
            } else if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode) {
                branchTargets.addAll(((org.objectweb.asm.tree.TableSwitchInsnNode) insn).labels);
                branchTargets.add(((org.objectweb.asm.tree.TableSwitchInsnNode) insn).dflt);
            }
        }
        if (method.tryCatchBlocks != null) {
            for (org.objectweb.asm.tree.TryCatchBlockNode tcb : method.tryCatchBlocks) {
                branchTargets.add(tcb.start);
                branchTargets.add(tcb.end);
                branchTargets.add(tcb.handler);
            }
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ATHROW) {
                continue;
            }
            // Walk backward from the athrow over the exception construction to the guarding jump.
            // The region must contain the CCE construction and the anchor string; debug-info
            // labels are skipped, but a real branch target inside means someone can jump into
            // the middle of the block - in that case do not touch it.
            boolean sawCce = false;
            boolean sawAnchor = false;
            AbstractInsnNode cursor = insn.getPrevious();
            AbstractInsnNode guard = null;
            while (cursor != null) {
                if (cursor instanceof JumpInsnNode) {
                    guard = cursor;
                    break;
                }
                if (branchTargets.contains(cursor)) {
                    break; // control flow can enter the throw block - do not touch
                }
                if (cursor instanceof org.objectweb.asm.tree.LabelNode
                        || cursor instanceof org.objectweb.asm.tree.LineNumberNode
                        || cursor instanceof org.objectweb.asm.tree.FrameNode) {
                    cursor = cursor.getPrevious();
                    continue;
                }
                if (cursor instanceof TypeInsnNode && cursor.getOpcode() == Opcodes.NEW
                        && "java/lang/ClassCastException".equals(((TypeInsnNode) cursor).desc)) {
                    sawCce = true;
                }
                if (cursor instanceof LdcInsnNode && ((LdcInsnNode) cursor).cst instanceof String
                        && ((String) ((LdcInsnNode) cursor).cst).contains(ANCHOR_SUBSTRING)) {
                    sawAnchor = true;
                }
                cursor = cursor.getPrevious();
            }
            if (!sawCce || !sawAnchor || guard == null) {
                continue;
            }
            if (guard.getOpcode() != Opcodes.IFEQ && guard.getOpcode() != Opcodes.IFNE) {
                continue;
            }
            // The guard must jump AROUND the throw (target after the athrow), i.e. it is the
            // "condition false -> skip the exception" jump produced by the source-level if.
            boolean targetAfterThrow = false;
            for (AbstractInsnNode after = insn.getNext(); after != null; after = after.getNext()) {
                if (after == ((JumpInsnNode) guard).label) {
                    targetAfterThrow = true;
                    break;
                }
            }
            if (!targetAfterThrow) {
                continue;
            }
            // Delete the exception construction block (everything between the guard and the
            // athrow, the athrow included; metadata-only nodes stay so debug tables keep valid
            // label references). Both guard paths then converge on the skip label - the check
            // is gone, and no branch target, frame or the guard itself is modified.
            java.util.List<AbstractInsnNode> toRemove = new java.util.ArrayList<>();
            for (AbstractInsnNode c = guard.getNext(); c != null && c != insn; c = c.getNext()) {
                if (!(c instanceof org.objectweb.asm.tree.LabelNode)
                        && !(c instanceof org.objectweb.asm.tree.LineNumberNode)
                        && !(c instanceof org.objectweb.asm.tree.FrameNode)) {
                    toRemove.add(c);
                }
            }
            if (toRemove.isEmpty()) {
                continue;
            }
            toRemove.add(insn);
            // Remove each node exactly once - reusing a node reference after InsnList.remove
            // corrupts the list's internal links and size.
            for (AbstractInsnNode nodeToRemove : toRemove) {
                method.instructions.remove(nodeToRemove);
            }
            return true;
        }
        return false;
    }
}
