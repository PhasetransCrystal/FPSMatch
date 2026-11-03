package net.ptcrys.fpsmatch.editor.common.domain.weapon.assets;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.*;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.core.packio.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact original TaCZ 1.1.7-hotfix default assets; never parses user supplied JSON. */
public final class TaczBuiltinAssetFormat {

    public static final EditorId BYTES = LrBuiltinAssetFormat.BYTES;
    public static final EditorId CODEC = LrBuiltinAssetFormat.CODEC;
    public static final String PROFILE = "tacz-1.1.7-hotfix-default";
    private static final String SCRIPT_ROOT = "assets/tacz/custom/tacz_default_gun/assets/tacz/scripts/";
    // Generated once from the SHA-pinned original JAR. Guns precede displays, each ordered by id.
    private static final List<Asset> ASSETS = List.of(
            new Asset("tacz:aa12", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/aa12.json", "ac0769434be057cf6d1764cf22378b96e8c3aeed31b483a87c84cce99a35586b", 172, "tacz.gun.aa12.name", "tacz:aa12_display"),
            new Asset("tacz:ai_awp", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/ai_awp.json", "7342707caf7cd7bda072f0fc41d927fa861b33ff060189e1bd754fd781df07de", 165, "tacz.gun.ai_awp.name", "tacz:ai_awp_display"),
            new Asset("tacz:ak47", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/ak47.json", "dc6ee0cd1c9f3a4c096247834aff1ff691e2dfbcc68d7e193ca9b0da07333735", 632, "tacz.gun.ak47.name", "tacz:ak47_display"),
            new Asset("tacz:aug", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/aug.json", "baa1b0ecac554af59ed08e0728ae4cb6181d489c6ec778170596676a55911e26", 167, "tacz.gun.aug.name", "tacz:aug_display"),
            new Asset("tacz:b93r", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/b93r.json", "badf21f76630c31888f147aabcd74a0238dc4ff10c1521c78a1d96bac4af5600", 157, "tacz.gun.b93r.name", "tacz:b93r_display"),
            new Asset("tacz:cz75", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/cz75.json", "29b4a6b1119a9170826de4528850188bd6bf32e7537d8f2cbbd75f13de13c94c", 157, "tacz.gun.cz75.name", "tacz:cz75_display"),
            new Asset("tacz:db_long", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/db_long.json", "7b32df2f98d86fb873eb0dbfd6e3583b39337868b5b8162c7e482082fff6c752", 170, "tacz.gun.db_long.name", "tacz:db_long_display"),
            new Asset("tacz:db_short", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/db_short.json", "2c25db53b315200024bbe6d4caa174f567d08cbe51bae77f25ea8ac5c1d42e3e", 174, "tacz.gun.db_short.name", "tacz:db_short_display"),
            new Asset("tacz:deagle", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/deagle.json", "df80ea5ee38229925cddab2c6a5f57a923004fa6d78d0756d9f747fdc91b731e", 165, "tacz.gun.deagle.name", "tacz:deagle_display"),
            new Asset("tacz:deagle_golden", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/deagle_golden.json", "d5b3c0fb46453ad2393ac82d10e90401120c83eac86cbd5cbbffcf02d4257282", 193, "tacz.gun.deagle_golden.name", "tacz:deagle_golden_display"),
            new Asset("tacz:fn_evolys", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/fn_evolys.json", "de96772bbcd3989b7c3220ce0ae33016e277b7489cb34c67924fde7ec69d5f0f", 187, "tacz.gun.fn_evolys.name", "tacz:fn_evolys_display"),
            new Asset("tacz:fn_fal", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/fn_fal.json", "266fffe8d976f595684251e48bab9a78af088dc087b501b981f1c15d487cf071", 641, "tacz.gun.fn_fal.name", "tacz:fn_fal_display"),
            new Asset("tacz:g36k", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/g36k.json", "23bafedfe4d1d6df1c8810f0aa2399949d5dcff4aaf0bec6ea8d4d8d1a919254", 171, "tacz.gun.g36k.name", "tacz:g36k_display"),
            new Asset("tacz:glock_17", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/glock_17.json", "e1043ad198958a4fc8f54891e46fc009037df2268d0133eafbc50f142bc9a946", 173, "tacz.gun.glock_17.name", "tacz:glock_17_display"),
            new Asset("tacz:hk416d", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/hk416d.json", "f05d1d0c1b77289fc21a6240d05a40bb20b1b73bf51bcea796b793526ae95f1d", 178, "tacz.gun.hk416d.name", "tacz:hk416d_display"),
            new Asset("tacz:hk_g3", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/hk_g3.json", "7c6c4e92cd065c9d6b9f5fa9aa0d1438070d0d6b68d4a78d9a550af4f2b8090c", 174, "tacz.gun.hk_g3.name", "tacz:hk_g3_display"),
            new Asset("tacz:hk_mp5a5", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/hk_mp5a5.json", "44f01865faeeac5ae616109fcab033ae445aad43a39c5959799107873f189ba8", 170, "tacz.gun.hk_mp5a5.name", "tacz:hk_mp5a5_display"),
            new Asset("tacz:m1014", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m1014.json", "aadcf6ccb9bffe13d3b94e2efdecf6a61c2dba5138ed61dcfe1623497b7d6e8f", 224, "tacz.gun.m1014.name", "tacz:m1014_display"),
            new Asset("tacz:m107", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m107.json", "08b0bb6a9dee7f96df3ea536aa2f18f487b34cc94a894261de65929e84b293df", 157, "tacz.gun.m107.name", "tacz:m107_display"),
            new Asset("tacz:m16a1", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m16a1.json", "91ef33df237fa69dd5009f3bb474b25cacaec67158230a33536ec6979215a9aa", 174, "tacz.gun.m16a1.name", "tacz:m16a1_display"),
            new Asset("tacz:m16a4", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m16a4.json", "5a679ea814f703ea4cde5d33ef5e5c4d77f80fc97c74a73fefebe50c5671739c", 174, "tacz.gun.m16a4.name", "tacz:m16a4_display"),
            new Asset("tacz:m1911", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m1911.json", "b4af70434111b24618dd5c047100fc3ae63abf269cd68062c1611997092214d8", 161, "tacz.gun.m1911.name", "tacz:m1911_display"),
            new Asset("tacz:m249", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m249.json", "cb1d463c1fb15de8a3d59bc5415a01e0e8ed0604dbde48f7ddf65dc082d94489", 167, "tacz.gun.m249.name", "tacz:m249_display"),
            new Asset("tacz:m320", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m320.json", "56aa8430a8a597aebf2048a0d72018c1061cc4f104e2bac735f5a3ff006885ba", 154, "tacz.gun.m320.name", "tacz:m320_display"),
            new Asset("tacz:m4a1", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m4a1.json", "4aadf344ca7145e0913dcebb788c6b335b1ebb5293f12bdb71eb613448f2459e", 170, "tacz.gun.m4a1.name", "tacz:m4a1_display"),
            new Asset("tacz:m700", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m700.json", "b1908fe8336d09cd040f4e68d6bb5672f0c6d33f94175effba5a8f10b2ddc7b0", 157, "tacz.gun.m700.name", "tacz:m700_display"),
            new Asset("tacz:m870", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m870.json", "72cb687826ff42665423e9e7e5100a5a67d2560a2d4ec8527cf88c0a1d0ee325", 220, "tacz.gun.m870.name", "tacz:m870_display"),
            new Asset("tacz:m95", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/m95.json", "088f9f5d9504488720611895076b53803590ef65c9cd194652d2fd043ce90b3f", 153, "tacz.gun.m95.name", "tacz:m95_display"),
            new Asset("tacz:minigun", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/minigun.json", "bb6c6cf9ac2eb7db8dbb596542c122132396f488eefe12c395cafd0a601eec0f", 641, "tacz.gun.minigun.name", "tacz:minigun_display"),
            new Asset("tacz:mk14", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/mk14.json", "112632a48df9abfc989f56a17681cba2f92c360439e14ef40284975f4be3772b", 170, "tacz.gun.mk14.name", "tacz:mk14_display"),
            new Asset("tacz:p320", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/p320.json", "e68b3af3014b0a8b977282a979042a911d488a9cd91d23353a02c49d47fbff84", 157, "tacz.gun.p320.name", "tacz:p320_display"),
            new Asset("tacz:p90", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/p90.json", "81b60c425ae3f434cd1a540ac4eff730fcb4a833f3d6e147b7c57b399c1b312c", 150, "tacz.gun.p90.name", "tacz:p90_display"),
            new Asset("tacz:qbz_191", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/qbz_191.json", "a900be959f4e9761f2f389d3f1d4838391be1be25405eca9fed87e27efe7056d", 645, "tacz.gun.qbz_191.name", "tacz:qbz_191_display"),
            new Asset("tacz:qbz_95", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/qbz_95.json", "c9162ef07f624bec55a16bfeb18ff1a8c5d0619a3b250db67de3d2a035f573e9", 640, "tacz.gun.qbz_95.name", "tacz:qbz_95_display"),
            new Asset("tacz:rpg7", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/rpg7.json", "57db4ae2954c15cf5e93b08971a8617db2449b301f6392977e731a905d98d8c6", 154, "tacz.gun.rpg7.name", "tacz:rpg7_display"),
            new Asset("tacz:rpk", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/rpk.json", "2874f419e148606311384a8c53399746e267563ab1f2536127c4428de7ea89a7", 197, "tacz.gun.rpk.name", "tacz:rpk_display"),
            new Asset("tacz:scar_h", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/scar_h.json", "e89ff1587a3ed57a6c66b3240a2fcb3aefd6fcd1655d339c3c414dd51ba512b3", 178, "tacz.gun.scar_h.name", "tacz:scar_h_display"),
            new Asset("tacz:scar_l", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/scar_l.json", "c56cfb4f4564cf37b038eff7856827fce0ccfc4dcd1d42a61ad83afddbdb7ae0", 178, "tacz.gun.scar_l.name", "tacz:scar_l_display"),
            new Asset("tacz:sks_tactical", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/sks_tactical.json", "ac5e159e5cccc76abbd31e6e521d1d1d7822ea2522a03a608e0db12e8bfdfd50", 188, "tacz.gun.sks_tactical.name", "tacz:sks_tactical_display"),
            new Asset("tacz:spas_12", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/spas_12.json", "e6e7bb6b668f6c5b5cd935d6f056fafb88c8931ceb163e5c3d7c024163d58cca", 220, "tacz.gun.spas_12.name", "tacz:spas_12_display"),
            new Asset("tacz:spr15hb", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/spr15hb.json", "a6cb687bce599ed79cc47f9d4245682b2dad0143cc13afc40b8eca035485adfb", 182, "tacz.gun.spr15hb.name", "tacz:spr15hb_display"),
            new Asset("tacz:springfield1873", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/springfield1873.json", "32420ab592ab508630604737b1879013db0e007fa6987849abc27c1845ab95f8", 201, "tacz.gun.springfield1873.name", "tacz:springfield1873_display"),
            new Asset("tacz:timeless50", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/timeless50.json", "6322390ad8d666735793d857c2466a79d4e780338afcd09472e1d9841cd54800", 181, "tacz.gun.timeless50.name", "tacz:timeless50_display"),
            new Asset("tacz:type_81", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/type_81.json", "cc6f3ac96fb161a459eff47f330beec4469098307dc3118d530322a2d3ede320", 644, "tacz.gun.type_81.name", "tacz:type_81_display"),
            new Asset("tacz:ump45", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/ump45.json", "fcbaa0db548e39d6e0f2054de92384ea986eeca86c9858b56f677165db3c67af", 158, "tacz.gun.ump45.name", "tacz:ump45_display"),
            new Asset("tacz:uzi", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/uzi.json", "196fc154bf7f0ab3de911e1baf0e42f790a379630008bfb27c10fe1f624abade", 150, "tacz.gun.uzi.name", "tacz:uzi_display"),
            new Asset("tacz:vector45", WeaponItemValues.TACZ_GUN_ASSET, "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/vector45.json", "31965e548657fb596cac2bce2b691d0f5dd49aa28220ea2870c3e2d11b4e85ee", 170, "tacz.gun.vector45.name", "tacz:vector45_display"),
            new Asset("tacz:aa12_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/aa12_display.json", "d84fe59b1ad1c9c0b7d35497b626c21909ecba603ea6816200037759f2cfd40e", 1608, "tacz.gun.aa12.name", null),
            new Asset("tacz:ai_awp_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/ai_awp_display.json", "ffed171cc29f786a0876ed21e6584c0e7587c870627a3a9341ea814202574cb4", 1554, "tacz.gun.ai_awp.name", null),
            new Asset("tacz:ak47_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/ak47_display.json", "2ac4fe9ee59a42ba107fa1119af4eb483371d52bf586a0c8a1518f832ac93d34", 7311, "tacz.gun.ak47.name", null),
            new Asset("tacz:aug_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/aug_display.json", "431da8cba686f0a187198eca13a8a60b79490a17c0a0b45b83952fb99f69d068", 1581, "tacz.gun.aug.name", null),
            new Asset("tacz:b93r_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/b93r_display.json", "661231a605c0fa988b082d3b4722fef38f1c65bf76b5e758831ad7dbbebd9802", 1384, "tacz.gun.b93r.name", null),
            new Asset("tacz:cz75_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/cz75_display.json", "eb68f416fc399bacdd3eb0bb7c1dced998986c3652659632d87755774737cbb0", 1253, "tacz.gun.cz75.name", null),
            new Asset("tacz:db_long_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/db_long_display.json", "c13dcac2fc48add71b06fe7f9ace6b7dc24bda24c09a2d7b55f9ca3424cdcc1e", 1391, "tacz.gun.db_long.name", null),
            new Asset("tacz:db_short_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/db_short_display.json", "884b6c4e7f6415ab5a079e4d3b52607f777254dcd653d875e9df0d825ccf8afa", 1341, "tacz.gun.db_short.name", null),
            new Asset("tacz:deagle_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/deagle_display.json", "ffeb4cc01c7387a3c208aaad64d7bdad3b48745fba2113c14db16973bd28757d", 1546, "tacz.gun.deagle.name", null),
            new Asset("tacz:deagle_golden_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/deagle_golden_display.json", "4c17c22d8f9442156955bf101b3ebef1cb2e273eb7521b923cc0698b2c2fb8f7", 1704, "tacz.gun.deagle_golden.name", null),
            new Asset("tacz:fn_evolys_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/fn_evolys_display.json", "a2cc6dccc3b17b74936fb6ce09a93c658a6a67354c70300e443f707c6d10b1da", 1931, "tacz.gun.fn_evolys.name", null),
            new Asset("tacz:fn_fal_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/fn_fal_display.json", "01c9bf62949c874305ce63a7aea40e895f5e38451860ca2db0f0382e3560c50e", 2461, "tacz.gun.fn_fal.name", null),
            new Asset("tacz:g36k_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/g36k_display.json", "7705bc7aee485a9b008b72159245a366c037ab902c41c3f5ea2d932484d5f3e7", 1666, "tacz.gun.g36k.name", null),
            new Asset("tacz:glock_17_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/glock_17_display.json", "442c49a4a4a8552c571cfdf13d6a86250ac6c4dc76011fb883503bc71f2bfb79", 1588, "tacz.gun.glock_17.name", null),
            new Asset("tacz:hk416d_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/hk416d_display.json", "e032ee42fdbed8bc40a4c5139e47eabd694090b47eb50a635449951ec6cced49", 1543, "tacz.gun.hk416d.name", null),
            new Asset("tacz:hk_g3_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/hk_g3_display.json", "e2ccbc78aa288208a8e4163e3d5cbc5a006c32b859e611f04800358ddc8f8aa8", 1898, "tacz.gun.hk_g3.name", null),
            new Asset("tacz:hk_mp5a5_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/hk_mp5a5_display.json", "f41e3cacc7e4098f373cd5f9039c223a4923a62d4b9c5b3c37a6fe434d7016f4", 1758, "tacz.gun.hk_mp5a5.name", null),
            new Asset("tacz:m1014_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m1014_display.json", "cd912c3ec516c327f90212b44482b56c5b71993b23147c402e81d6e163cb2a24", 2227, "tacz.gun.m1014.name", null),
            new Asset("tacz:m107_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m107_display.json", "148697f57ef91aa128ee6a2fc8973d24a895e95c6866fbca792a4c8b3fdc5106", 1613, "tacz.gun.m107.name", null),
            new Asset("tacz:m16a1_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m16a1_display.json", "17c4fdaac0864896159ce376c28a3663b20a57504189c452dc0d6320517b9815", 1682, "tacz.gun.m16a1.name", null),
            new Asset("tacz:m16a4_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m16a4_display.json", "b8535206d4e0c7975275cc0e82690c425a33cafb1cda1ac66f9fe9c82c84d704", 1573, "tacz.gun.m16a4.name", null),
            new Asset("tacz:m1911_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m1911_display.json", "a42100ee255512ba7087a0e8c124baf76497b0fbd679587a1cc33d71bbef7836", 1371, "tacz.gun.m1911.name", null),
            new Asset("tacz:m249_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m249_display.json", "fc7ff00447af6b3a8259a35dcd909df6fbd3e4e29038c38e70950979e2650b8c", 1712, "tacz.gun.m249.name", null),
            new Asset("tacz:m320_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m320_display.json", "333e306b1cf3a2f650b0808f54385375c574969d93a8e81f56515e2619b00856", 1172, "tacz.gun.m320.name", null),
            new Asset("tacz:m4a1_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m4a1_display.json", "35cd72133605ddfb95ec01fbbf33cad288dd4444082a7d839f55cb34c645fb22", 1655, "tacz.gun.m4a1.name", null),
            new Asset("tacz:m700_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m700_display.json", "e8d09a05c9a9dc3357a02805e6c480561957f987f7768adb31f0c278774b7a03", 2162, "tacz.gun.m700.name", null),
            new Asset("tacz:m870_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m870_display.json", "f9f9a9c8bb69d0e939ddbf06158c2dd4e92f86d539808df23bef1c4e7dd3c3ca", 2187, "tacz.gun.m870.name", null),
            new Asset("tacz:m95_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/m95_display.json", "5eb0194d87ac05251d0d7a51b8728de53fa74d332c02efc67ede4fd03d58d255", 1314, "tacz.gun.m95.name", null),
            new Asset("tacz:minigun_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/minigun_display.json", "a927586be8aa98e142ababda0ab59764836b8ed09b0fa75e9755bc2be0307bb1", 7176, "tacz.gun.minigun.name", null),
            new Asset("tacz:mk14_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/mk14_display.json", "295fe898425f51ae8f63c0282b7950dc62b96925ba061402328a3f5e7d12e4bc", 1822, "tacz.gun.mk14.name", null),
            new Asset("tacz:p320_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/p320_display.json", "a00567bf3378cacb5426019445d65ba170982996985bc7543fbff0f7e72055ad", 1330, "tacz.gun.p320.name", null),
            new Asset("tacz:p90_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/p90_display.json", "47ca79ce9723edad9ab36104a48f8b0c2ae080d4fa81876e0f7d0b895f2a92be", 1574, "tacz.gun.p90.name", null),
            new Asset("tacz:qbz_191_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/qbz_191_display.json", "e69f3fc5653cbf996511b75c196b63082ff70c3ef596b8be531e71065c46f14b", 1902, "tacz.gun.qbz_191.name", null),
            new Asset("tacz:qbz_95_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/qbz_95_display.json", "00341747df4b7211d362f84530753ac3f35dc501b67515210ba3c9a8146f7e37", 1840, "tacz.gun.qbz_95.name", null),
            new Asset("tacz:rpg7_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/rpg7_display.json", "18a470adcd5bb58f3f8676c9e677bf1a42833ea5de58eec8de4037e99f8d91db", 1106, "tacz.gun.rpg7.name", null),
            new Asset("tacz:rpk_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/rpk_display.json", "36975f4c28f9c8ca7109a5fd3a23f3db7f670d5be7dd759243e83f0ef402873c", 4813, "tacz.gun.rpk.name", null),
            new Asset("tacz:scar_h_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/scar_h_display.json", "ab4b12428aa58d697b213119cc23961dff4769b9c6c2a072b2350af909597c2b", 1618, "tacz.gun.scar_h.name", null),
            new Asset("tacz:scar_l_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/scar_l_display.json", "1e5551c351e805227ace7f84619917f09de09f16706e3ef0793974997b619cc4", 1618, "tacz.gun.scar_l.name", null),
            new Asset("tacz:sks_tactical_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/sks_tactical_display.json", "f8cabcd271af91777bac80bd18586b76b0f25e4156ce87294eb898eaf69b63e6", 1639, "tacz.gun.sks_tactical.name", null),
            new Asset("tacz:spas_12_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/spas_12_display.json", "b8b2ab8eff280fa62d18ae10d16513d6afd6b21af2b9cdd10995fc8f56e17e52", 2292, "tacz.gun.spas_12.name", null),
            new Asset("tacz:spr15hb_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/spr15hb_display.json", "9b550489c409f3457a71160ae1f8b4b3f99642a649ac4f136f19cf3f90551284", 1452, "tacz.gun.spr15hb.name", null),
            new Asset("tacz:springfield1873_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/springfield1873_display.json", "d5f8bb9e212c050a3efbc3992a0c04b3305a62863aca9571d6a376ddc4fbae70", 1765, "tacz.gun.springfield1873.name", null),
            new Asset("tacz:timeless50_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/timeless50_display.json", "d2263c630a247041a7c1c744560565d07a82170434b630de75d30da68e0f45d8", 1758, "tacz.gun.timeless50.name", null),
            new Asset("tacz:type_81_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/type_81_display.json", "940c65af69bfa2b1041375b24531039e64f7061bea033563c0632dc9eb736ed0", 6896, "tacz.gun.type_81.name", null),
            new Asset("tacz:ump45_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/ump45_display.json", "52adb6eb80c58bf43e0cccb1ad4994aa6d630ccd6aaa4f75625ea86b22450c05", 1634, "tacz.gun.ump45.name", null),
            new Asset("tacz:uzi_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/uzi_display.json", "cf262bc34cc2e83c2301f3d4c835dd09acc5f19186d51eb96cfeea075c6864e2", 1572, "tacz.gun.uzi.name", null),
            new Asset("tacz:vector45_display", WeaponItemValues.TACZ_DISPLAY_ASSET, "assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/vector45_display.json", "9de06229f04315a1d46f090adf64a808dd31e01c8f4ef78a505012295a4287dc", 1416, "tacz.gun.vector45.name", null));
    private static final Set<String> SCRIPTS = Set.of(
            "tacz_ak47_state_machine",
            "tacz_b93r_state_machine",
            "tacz_db_short_state_machine",
            "tacz_deagle_state_machine",
            "tacz_default_state_machine",
            "tacz_devotion_lmg_state_machine",
            "tacz_fn_evolys_state_machine",
            "tacz_fn_fal_state_machine",
            "tacz_g36k_state_machine",
            "tacz_hk416d_state_machine",
            "tacz_hk_g3_state_machine",
            "tacz_hk_mp5a5_state_machine",
            "tacz_m1014_state_machine",
            "tacz_m249_state_machine",
            "tacz_m870_state_machine",
            "tacz_manual_action_state_machine",
            "tacz_minigun_state_machine",
            "tacz_qbz_191_state_machine",
            "tacz_scar_h_state_machine",
            "tacz_scar_l_state_machine",
            "tacz_sks_tactical_state_machine",
            "tacz_spas_12_state_machine",
            "tacz_spr15hb_state_machine",
            "tacz_timeless50_state_machine",
            "tacz_vector45_state_machine");

    private TaczBuiltinAssetFormat() {}

    public static List<Asset> assets() {
        return ASSETS;
    }

    public static final class Asset {

        private final EditorId id, type, defaultDisplay;
        private final String path, sha256, labelKey;
        private final long size;

        private Asset(String id, EditorId type, String path, String sha256, long size,
                      String labelKey, String defaultDisplay) {
            this.id = EditorId.parse(id);
            this.type = type;
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
            this.labelKey = labelKey;
            this.defaultDisplay = defaultDisplay == null ? null : EditorId.parse(defaultDisplay);
        }

        public EditorId id() {
            return id;
        }

        public EditorId type() {
            return type;
        }

        public String path() {
            return path;
        }

        public String sha256() {
            return sha256;
        }

        public long size() {
            return size;
        }

        public String labelKey() {
            return labelKey;
        }

        public EditorId defaultDisplay() {
            return defaultDisplay;
        }

        public Map<String, String> settings() {
            return Map.of("assetId", id.toString(), "profile", PROFILE, "indexPath", path);
        }

        /** A structural candidate; actual source authentication remains mandatory. */
        public ResourceCatalog addTo(ResourceCatalog existing, UUID resource, UUID version, UUID source,
                                     BlobRef blob, UUID defaultPackVersion, UUID ordinaryPackVersion,
                                     UUID defaultDisplayVersion) {
            Objects.requireNonNull(existing);
            Objects.requireNonNull(defaultPackVersion);
            Objects.requireNonNull(ordinaryPackVersion);
            if ((defaultDisplay == null) != (defaultDisplayVersion == null)) throw new IllegalArgumentException("default display dependency");
            if (!matchesBlob(blob)) throw new IllegalArgumentException("unexpected builtin asset source");
            if (existing.resources().containsKey(resource) || existing.versions().containsKey(version) || existing.byteEntries().containsKey(source)) throw new IllegalArgumentException("existing asset identity");
            Set<UUID> dependencies = defaultDisplay == null ? Set.of(defaultPackVersion, ordinaryPackVersion) : Set.of(defaultPackVersion, ordinaryPackVersion, defaultDisplayVersion);
            var resources = new HashMap<>(existing.resources());
            var versions = new HashMap<>(existing.versions());
            var entries = new HashMap<>(existing.byteEntries());
            resources.put(resource, new LogicalResource(resource, type, id.toString(), version));
            versions.put(version, new ResourceVersion(version, resource, WeaponItemValues.TACZ_IMPORTER, "1", settings(), source, dependencies, Set.of()));
            entries.put(source, new ProjectState.Entry(source, WeaponItemValues.TACZ_MODULE.toString(), BYTES.toString(),
                    CODEC.toString(), 1, path, blob, Set.of(), Set.of()));
            var result = new ResourceCatalog(resources, versions, entries);
            ResourceStorageBridge.planAll(result);
            return result;
        }

        private boolean matchesBlob(BlobRef blob) {
            return blob.kind() == BlobRef.Kind.ASSET && blob.size() == size && blob.sha256().equals(sha256);
        }
    }

    /** Fixed structural identity for a catalog that has already passed real content validation. */
    public static Optional<Asset> asset(ResourceVersion version, EditorId type) {
        if (!version.importer().equals(WeaponItemValues.TACZ_IMPORTER) || !version.importerVersion().equals("1")) return Optional.empty();
        return ASSETS.stream().filter(asset -> asset.type.equals(type) && asset.settings().equals(version.settings())).findFirst();
    }

    public static void register(ExtensionRegistryBuilder builder) {
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        var producer = new ContributionKey(ResourceProducers.KIND.id(), WeaponItemValues.TACZ_IMPORTER);
        builder.add(ResourceProducers.KIND, new Contribution(producer, WeaponItemValues.TACZ_MODULE,
                Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
        builder.add(ResourceContentValidators.KIND, new Contribution(
                new ContributionKey(ResourceContentValidators.KIND.id(), WeaponItemValues.TACZ_IMPORTER),
                WeaponItemValues.TACZ_MODULE, Contribution.Side.COMMON, Set.of(producer)), TaczBuiltinAssetFormat::validate);
    }

    private static Asset shape(ResourceVersion version, ResourceContent content) {
        if (version.sourceEntry() == null || !version.outputEntries().isEmpty()) return null;
        Asset asset = asset(version, content.resourceTypes().get(version.resourceId())).orElse(null);
        if (asset == null) return null;
        var entry = content.byteEntries().get(version.sourceEntry());
        return entry != null && entry.ownerModule().equals(WeaponItemValues.TACZ_MODULE.toString()) && entry.typeId().equals(BYTES.toString()) && entry.codecId().equals(CODEC.toString()) && entry.schemaVersion() == 1 && entry.documentDependencies().isEmpty() && entry.assetDependencies().isEmpty() && asset.matchesBlob(entry.content()) ? asset : null;
    }

    private static List<Problem> validate(ResourceVersion version, ResourceContent content) throws IOException {
        content.checkCancelled();
        Asset asset = shape(version, content);
        if (asset == null) return invalid(version, "descriptor");
        ResourceVersion defaults = null, ordinary = null, display = null;
        for (UUID dependency : version.dependencyVersions()) {
            content.checkCancelled();
            var candidate = content.versions().get(dependency);
            if (candidate == null) return invalid(version, "dependency");
            var source = BuiltinWeaponPackFormat.matchingSource(candidate, content);
            if (source == BuiltinWeaponPackFormat.Source.TACZ_DEFAULT && defaults == null) defaults = candidate;
            else if (source == BuiltinWeaponPackFormat.Source.TACZ_RESOURCES && ordinary == null) ordinary = candidate;
            else {
                Asset displayAsset = shape(candidate, content);
                if (asset.defaultDisplay == null || display != null || displayAsset == null || displayAsset.defaultDisplay != null || !asset.defaultDisplay.equals(displayAsset.id)) return invalid(version, "dependency type");
                display = candidate;
            }
        }
        if (defaults == null || ordinary == null || asset.defaultDisplay != null && display == null) return invalid(version, "closure");
        Set<UUID> sources = Set.of(defaults.id(), ordinary.id());
        if (display != null && !display.dependencyVersions().equals(sources) || display == null && !version.dependencyVersions().equals(sources)) return invalid(version, "source identity");
        content.copyContent(version.sourceEntry(), OutputStream.nullOutputStream());
        if (display != null) content.copyContent(display.sourceEntry(), OutputStream.nullOutputStream());
        content.checkCancelled();
        return List.of();
    }

    /**
     * Shared member/script checks run once per referenced exact source version, without a cache.
     * Full capture validation must run every contribution before granting a usable catalog.
     */
    static List<Problem> validatePack(ResourceVersion version, ResourceContent content) throws IOException {
        content.checkCancelled();
        if (BuiltinWeaponPackFormat.matchingSource(version, content) != BuiltinWeaponPackFormat.Source.TACZ_DEFAULT)
            return invalid(version, "source descriptor");
        BooleanSupplier cancelled = () -> {
            try {
                content.checkCancelled();
                return false;
            } catch (ResourceValidationCancelledException failure) {
                throw new CancellationSignal(failure);
            } catch (RuntimeException | Error failure) {
                throw new SupplierSignal(failure);
            }
        };
        try (var physical = Physical.open(version, content, cancelled)) {
            var snapshot = physical.snapshot;
            if (snapshot.index().entries().size() != BuiltinWeaponPackFormat.Source.TACZ_DEFAULT.entryCount())
                return invalid(version, "member count");
            var paths = new HashMap<String, PackEntry>();
            for (var entry : snapshot.index().entries()) {
                content.checkCancelled();
                paths.put(entry.path(), entry);
            }
            for (Asset expected : ASSETS) {
                content.checkCancelled();
                var entry = paths.get(expected.path);
                if (entry == null || entry.size() != expected.size || !entry.sha256().equals(expected.sha256)) return invalid(version, "source member");
                try (InputStream input = snapshot.openEntry(entry.path(), cancelled)) {
                    decode(input.readNBytes(Math.toIntExact(expected.size + 1)));
                }
            }
            var scripts = new TreeMap<String, String>();
            for (var entry : snapshot.index().entries()) {
                content.checkCancelled();
                if (!entry.path().startsWith(SCRIPT_ROOT) || !entry.path().endsWith(".lua")) continue;
                if (entry.size() > 1_048_576 || scripts.size() >= 256) return invalid(version, "script budget");
                String name = "tacz_" + entry.path().substring(SCRIPT_ROOT.length(), entry.path().length() - 4);
                byte[] bytes;
                try (InputStream input = snapshot.openEntry(entry.path(), cancelled)) {
                    bytes = input.readNBytes(1_048_577);
                }
                if (bytes.length != entry.size()) return invalid(version, "script bytes");
                if (scripts.put(name, decode(bytes)) != null) return invalid(version, "script collision");
            }
            if (!scripts.keySet().equals(SCRIPTS)) return invalid(version, "script domain");
            var validation = PinnedScriptValidator.validate(scripts, constants(), cancelled, 2_000_000);
            Set<String> required = new TreeSet<>();
            validation.requires().values().forEach(required::addAll);
            if (!required.equals(Set.of("tacz_default_state_machine", "tacz_manual_action_state_machine"))) return invalid(version, "script requires");
            content.checkCancelled();
            return List.of();
        } catch (CancellationSignal failure) {
            transferSuppressed(failure, failure.cancellation);
            throw failure.cancellation;
        } catch (SupplierSignal failure) {
            transferSuppressed(failure, failure.original);
            if (failure.original instanceof RuntimeException original) throw original;
            throw (Error) failure.original;
        } catch (PackSnapshotException failure) {
            if (failure.code() == PackSnapshotException.Code.CANCELLED) {
                var cancellation = new ResourceValidationCancelledException();
                cancellation.initCause(failure);
                throw cancellation;
            }
            throw failure;
        } catch (ScriptValidationException failure) {
            if (failure.reason() == ScriptValidationException.Reason.CANCELLED) throw new ResourceValidationCancelledException();
            return invalid(version, "script " + failure.reason().name());
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void transferSuppressed(Throwable wrapper, Throwable original) {
        for (Throwable failure : wrapper.getSuppressed()) if (failure != original) original.addSuppressed(failure);
    }

    private static ScriptConstants constants() {
        var text = new TreeMap<String, String>();
        for (String name : List.of("bayonet_muzzle", "bayonet_push", "bayonet_stock", "cancel_reload", "draw", "fire_select",
                "idle", "inspect", "put_away", "reload", "run", "shoot", "walk"))
            text.put("INPUT_" + name.toUpperCase(Locale.ROOT), name);
        text.put("INPUT_BOLT", "blot"); // Exact TACZ 1.1.7-hotfix constant, including its original spelling.
        return new ScriptConstants(text, Map.ofEntries(Map.entry("AUTO", 0), Map.entry("SEMI", 1), Map.entry("BURST", 2),
                Map.entry("UNKNOWN", 3), Map.entry("PLAY_ONCE_HOLD", 0), Map.entry("PLAY_ONCE_STOP", 1), Map.entry("LOOP", 2),
                Map.entry("NOT_RELOADING", 0), Map.entry("EMPTY_RELOAD_FEEDING", 1), Map.entry("EMPTY_RELOAD_FINISHING", 2),
                Map.entry("TACTICAL_RELOAD_FEEDING", 3), Map.entry("TACTICAL_RELOAD_FINISHING", 4)));
    }

    private static List<Problem> invalid(ResourceVersion version, String field) {
        return List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("fpsmatch:weapons/invalid_builtin_asset"),
                "resource:" + version.id(), new TextContent.Message("fpsmatch.editor.weapons.invalid_builtin_asset",
                        List.of(new TextContent.LiteralArgument(field)))));
    }

    private static final class CancellationSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;
        final ResourceValidationCancelledException cancellation;

        CancellationSignal(ResourceValidationCancelledException cancellation) {
            super(null, null, false, false);
            this.cancellation = cancellation;
        }
    }

    /** Keep supplier IllegalArgumentException out of archive format error conversion. */
    private static final class SupplierSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;
        final Throwable original;

        SupplierSignal(Throwable original) {
            super(null, null, false, false);
            this.original = original;
        }
    }

    /** Owns both its private materialization and its authenticated fixed channel. */
    private static final class Physical implements AutoCloseable {

        final Path path;
        final PackSnapshot snapshot;

        Physical(Path path, PackSnapshot snapshot) {
            this.path = path;
            this.snapshot = snapshot;
        }

        static Physical open(ResourceVersion pack, ResourceContent content, BooleanSupplier cancelled) throws IOException {
            Path path = Files.createTempFile("editor-tacz-asset-", ".edpack");
            try {
                try (OutputStream output = Files.newOutputStream(path)) {
                    content.copyContent(pack.sourceEntry(), output);
                }
                return new Physical(path, PackSnapshots.open(path, cancelled));
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        public void close() throws IOException {
            IOException failure = null;
            try {
                snapshot.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }
    }
}
