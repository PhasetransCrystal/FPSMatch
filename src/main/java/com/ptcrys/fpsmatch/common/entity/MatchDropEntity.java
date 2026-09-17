package com.ptcrys.fpsmatch.common.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.datafixers.util.Pair;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.common.drop.DropType;
import com.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import com.ptcrys.fpsmatch.compat.LrtacticalCompat;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.ptcrys.fpsmatch.compat.gun.GunDataDTO;
import com.ptcrys.fpsmatch.compat.gun.GunTabTypeEnum;
import com.ptcrys.fpsmatch.compat.gun.IGunProvider;
import com.ptcrys.fpsmatch.compat.impl.FPSMImpl;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.shop.ShopData;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Random;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MatchDropEntity extends Entity {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDropItem(ItemTossEvent event) {
        if (event.getPlayer().level().isClientSide) return;

        ItemStack itemStack = event.getEntity().getItem();
        FPSMCore.getInstance().getMapByPlayer(event.getPlayer()).ifPresent(map -> {
            DropType type = DropType.getItemDropType(itemStack);
            if (!event.isCanceled() && type != DropType.MISC) {
                FPSMUtil.playerDropMatchItem((ServerPlayer) event.getPlayer(), itemStack);
                event.setCanceled(true);
            }
        });
    }

    public static final EntityDataAccessor<Integer> DATA_TYPE = SynchedEntityData.defineId(MatchDropEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(MatchDropEntity.class, EntityDataSerializers.ITEM_STACK);
    private int pickupDelay;
    private boolean hasPlayedLandSound = false;
    private final float rotation = new Random().nextFloat(0f, 360f);

    public MatchDropEntity(Level pLevel, ItemStack itemStack, DropType type) {
        super(EntityRegister.MATCH_DROP_ITEM.get(), pLevel);
        this.pickupDelay = 20;
        this.setItem(itemStack);
        this.setDataType(type);
    }

    public MatchDropEntity(Level pLevel, ItemStack itemStack) {
        super(EntityRegister.MATCH_DROP_ITEM.get(), pLevel);
        this.pickupDelay = 20;
        this.setItem(itemStack);
        this.setDataType(DropType.getItemDropType(itemStack));
    }

    public MatchDropEntity(EntityType<? extends MatchDropEntity> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    public float getRotation() {
        return this.rotation;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_TYPE, 3);
        this.entityData.define(DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        if (this.getItem().isEmpty()) {
            this.discard();
        } else {
            super.tick();
            if (this.onGround() && !hasPlayedLandSound) {
                playLandSound(this.getItem());
                hasPlayedLandSound = true;
            } else {
                if (!this.onGround()) hasPlayedLandSound = false;
            }

            if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
                --this.pickupDelay;
            }

            this.xo = this.getX();
            this.yo = this.getY();
            this.zo = this.getZ();
            Vec3 vec3 = this.getDeltaMovement();
            float f = this.getEyeHeight() - 0.11111111F;
            net.minecraftforge.fluids.FluidType fluidType = this.getMaxHeightFluidType();
            if (!fluidType.isAir() && !fluidType.isVanilla() && this.getFluidTypeHeight(fluidType) > (double) f) {
                this.setDeltaMovement(vec3.x * (double) 0.99F, vec3.y + (double) (vec3.y < (double) 0.06F ? 5.0E-4F : 0.0F), vec3.z * (double) 0.99F);
            } else
                if (this.isInWater() && this.getFluidHeight(FluidTags.WATER) > (double) f) {
                    this.setUnderwaterMovement();
                } else if (this.isInLava() && this.getFluidHeight(FluidTags.LAVA) > (double) f) {
                    this.setUnderLavaMovement();
                } else if (!this.isNoGravity()) {
                    this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.04D, 0.0D));
                }

            if (this.level().isClientSide) {
                this.noPhysics = false;
            } else {
                this.noPhysics = !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-7D));
                if (this.noPhysics) {
                    this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0D, this.getZ());
                }
            }

            if (!this.onGround() || this.getDeltaMovement().horizontalDistanceSqr() > (double) 1.0E-5F || (this.tickCount + this.getId()) % 4 == 0) {
                this.move(MoverType.SELF, this.getDeltaMovement());
                float f1 = 0.98F;
                if (this.onGround()) {
                    BlockPos groundPos = getBlockPosBelowThatAffectsMyMovement();
                    f1 = this.level().getBlockState(groundPos).getFriction(level(), groundPos, this) * 0.98F;
                }

                this.setDeltaMovement(this.getDeltaMovement().multiply(f1, 0.98D, f1));
                if (this.onGround()) {
                    Vec3 vec31 = this.getDeltaMovement();
                    if (vec31.y < 0.0D) {
                        this.setDeltaMovement(vec31.multiply(1.0D, -0.5D, 1.0D));
                    }
                }
            }

            this.hasImpulse |= this.updateInWaterStateAndDoFluidPushing();
            if (!this.level().isClientSide) {
                double d0 = this.getDeltaMovement().subtract(vec3).lengthSqr();
                if (d0 > 0.01D) {
                    this.hasImpulse = true;
                }
            }

            ItemStack item = this.getItem();
            if (item.isEmpty() && !this.isRemoved()) {
                this.discard();
            }

        }
    }

    private void playLandSound(ItemStack itemStack) {
        if (!this.level().isClientSide) {
            IGunProvider provider = GunCompatManager.findProvider(itemStack);
            if (provider.isGun(itemStack)) {
                Optional<GunTabTypeEnum> type = provider.getGunData(itemStack).map(GunDataDTO::getGunTabType);
                type.ifPresent(t -> {
                    this.playSound(FPSMSoundRegister.getGunDropSound(t));
                });
            } else {
                SoundEvent sound;
                if (FPSMImpl.findLrtacticalMod() && LrtacticalCompat.isKnife(itemStack.getItem())) {
                    sound = FPSMSoundRegister.getKnifeDropSound();
                } else {
                    sound = FPSMSoundRegister.getItemDropSound(itemStack.getItem());
                }

                this.playSound(sound);
            }
        }
    }

    protected @NotNull BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.999999F);
    }

    private void setUnderwaterMovement() {
        Vec3 vec3 = this.getDeltaMovement();
        this.setDeltaMovement(vec3.x * (double) 0.99F, vec3.y + (double) (vec3.y < (double) 0.06F ? 5.0E-4F : 0.0F), vec3.z * (double) 0.99F);
    }

    private void setUnderLavaMovement() {
        Vec3 vec3 = this.getDeltaMovement();
        this.setDeltaMovement(vec3.x * (double) 0.95F, vec3.y + (double) (vec3.y < (double) 0.06F ? 5.0E-4F : 0.0F), vec3.z * (double) 0.95F);
    }

    public ItemStack getItem() {
        return this.entityData.get(DATA_ITEM);
    }

    private void setItem(ItemStack item) {
        this.entityData.set(DATA_ITEM, item);
    }

    public DropType getDropType() {
        return DropType.values()[this.entityData.get(DATA_TYPE)];
    }

    public void setDataType(DropType type) {
        this.entityData.set(DATA_TYPE, type.ordinal());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag pCompound) {
        this.setDataType(DropType.valueOf(pCompound.getString("DropType")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag pCompound) {
        pCompound.putString("DropType", this.getDropType().toString());
    }

    @Override
    public @NotNull InteractionResult interact(@NotNull Player player, @NotNull InteractionHand hand) {
        if (!this.level().isClientSide) {
            Inventory inventory = player.getInventory();
            List<ItemStack> items = FPSMUtil.searchInventoryForType(player.getInventory(), this.getDropType());
            ItemStack replace = this.getItem().copy();
            IGunProvider provider = GunCompatManager.findProvider(replace);
            if (provider.isGun(replace)) {
                Optional<GunTabTypeEnum> type = provider.getGunData(replace).map(GunDataDTO::getGunTabType);
                type.ifPresent(t -> {
                    this.playSound(FPSMSoundRegister.getGunPickupSound(t));
                });
            } else {
                this.playSound(FPSMSoundRegister.getItemPickSound(replace.getItem()));
            }
            if (!items.isEmpty()) {
                ItemStack origin = items.get(0);
                int slot = inventory.findSlotMatchingItem(origin);
                if (slot != -1) {
                    ItemStack copied = origin.copy();
                    inventory.setItem(slot, replace);
                    FPSMUtil.playerDropMatchItem((ServerPlayer) player, copied);
                } else {
                    inventory.add(replace);
                }
            } else {
                inventory.add(replace);
            }
            FPSMUtil.sortPlayerInventory((ServerPlayer) player);
            this.discard();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    public void playSound(@NotNull SoundEvent sound) {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                sound,
                this.getSoundSource(), 0.3F, 0.8F + this.random.nextFloat() * 0.4F);
    }

    public void playerTouch(@NotNull Player pEntity) {
        if (!this.level().isClientSide) {
            DropType type = this.getDropType();

            if (type == DropType.THROW && !type.canPickupThrowable(pEntity, this.getItem())) return;

            if (this.pickupDelay == 0 && type.inventoryMatch().test(pEntity)) {
                ItemStack itemStack = this.getItem();
                if (!itemStack.isEmpty()) {
                    ItemStack copy = itemStack.copy();
                    copy.setCount(1);
                    itemStack.shrink(1);

                    ShopCapability.getShopByPlayer((ServerPlayer) pEntity).ifPresent(shop -> {
                        ShopData<?> shopData = shop.getPlayerShopData(pEntity.getUUID());
                        Pair<? extends Enum<?>, ShopSlot> pair = shopData.checkItemStackIsInData(copy);
                        if (pair != null) {
                            ShopSlot slot = pair.getSecond();
                            slot.lock(copy.getCount());
                            shop.syncShopData((ServerPlayer) pEntity, pair.getFirst().name(), slot);
                        }
                    });

                    IGunProvider provider = GunCompatManager.findProvider(copy);
                    if (provider.isGun(copy)) {
                        Optional<GunTabTypeEnum> opt = provider.getGunData(copy).map(GunDataDTO::getGunTabType);
                        opt.ifPresent(t -> {
                            this.playSound(FPSMSoundRegister.getGunPickupSound(t));
                        });
                    } else {
                        this.playSound(FPSMSoundRegister.getItemPickSound(copy.getItem()));
                    }
                    pEntity.addItem(copy);
                    FPSMUtil.sortPlayerInventory((ServerPlayer) pEntity);
                } else {
                    this.discard();
                }
            }
        }
    }
}
