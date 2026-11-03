package net.ptcrys.fpsmatch.editor.core.compatibility;

/** Links the Q37-compiled four-argument constructor call against current public JARs. */
public final class EncodingBinaryCompatibility {
    private EncodingBinaryCompatibility() { }

    public static void main(String[] arguments) {
        byte[] source = new byte[] { 1, 2, 3 };
        var encoding = OldSamProvider.encoding(source);
        source[0] = 9;
        if (!encoding.notices().isEmpty() || encoding.bytes()[0] != 1) {
            throw new IllegalStateException("old constructor semantics changed");
        }
        byte[] returned = encoding.bytes();
        returned[0] = 8;
        if (encoding.bytes()[0] != 1) throw new IllegalStateException("encoding bytes leaked");
        System.out.println("LEGACY_ITEM_OLD_ENCODING_BINARY_PASS Q37-compiled four-argument constructor -> current empty notices and cloned bytes");
    }
}
