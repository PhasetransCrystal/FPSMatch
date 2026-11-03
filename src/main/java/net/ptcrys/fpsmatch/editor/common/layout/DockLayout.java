package net.ptcrys.fpsmatch.editor.common.layout;

/**
 * 停靠布局的根：schema 版本 + 不可变树。
 *
 * <p>
 * 当前唯一受支持版本为 {@value #SCHEMA_VERSION}。构造时立即执行整树验证，因此任何已存在的
 * {@code DockLayout} 都满足 CONTRACTS §8 的全部结构约束（恰好一个 {@code viewport}、节点/深度预算、
 * 叶ID全局唯一）。本类型不持有文档、选择或相机状态。
 *
 * @param schemaVersion 必须等于 {@value #SCHEMA_VERSION}
 * @param root          非 null 的停靠树根
 */
public record DockLayout(int schemaVersion, DockNode root) {

    /** 受支持的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    public DockLayout {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported dock layout schemaVersion: " + schemaVersion + " (expected " + SCHEMA_VERSION + ")");
        }
        if (root == null) {
            throw new IllegalArgumentException("dock layout root must not be null");
        }
        DockValidation.validate(root);
    }
}
