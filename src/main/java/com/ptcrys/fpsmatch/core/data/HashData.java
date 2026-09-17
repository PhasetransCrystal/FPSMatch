package com.ptcrys.fpsmatch.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.util.hash.HashAlgorithm;

public record HashData(String algorithm, String hash) {

    public static final Codec<HashData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("algorithm").forGetter(HashData::algorithm),
            Codec.STRING.fieldOf("hashData").forGetter(HashData::hash)).apply(instance, HashData::new));

    public HashAlgorithm getHashAlgorithm() {
        return HashAlgorithm.valueOf(algorithm);
    }
}
