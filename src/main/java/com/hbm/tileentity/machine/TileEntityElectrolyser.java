package com.hbm.tileentity.machine;

import com.hbm.interfaces.IControlReceiver;
import com.hbm.inventory.UpgradeManager;
import com.hbm.inventory.container.ContainerElectrolyserFluid;
import com.hbm.inventory.container.ContainerElectrolyserMetal;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.tank.FluidTank;
import com.hbm.inventory.gui.GUIElectrolyserFluid;
import com.hbm.inventory.gui.GUIElectrolyserMetal;
import com.hbm.inventory.material.MaterialShapes;
import com.hbm.inventory.material.Mats.MaterialStack;
import com.hbm.inventory.recipes.ElectrolyserFluidRecipes;
import com.hbm.inventory.recipes.ElectrolyserFluidRecipes.ElectrolysisRecipe;
import com.hbm.inventory.recipes.ElectrolyserMetalRecipes;
import com.hbm.items.machine.ItemMachineUpgrade.UpgradeType;
import com.hbm.main.MainRegistry;
import com.hbm.tileentity.IGUIProvider;
import com.hbm.tileentity.TileEntityMachineBase;
import com.hbm.util.fauxpointtwelve.DirPos;

import api.hbm.energy.IEnergyUser;
import api.hbm.fluid.IFluidStandardTransceiver;
import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

public class TileEntityElectrolyser extends TileEntityMachineBase implements IEnergyUser, IFluidStandardTransceiver, IControlReceiver, IGUIProvider {
	
	public long power;
	public static final long maxPower = 20000000;
	public static final int usageBase = 10000;
	public int usage;
	
	public int progressFluid;
	public static final int processFluidTimeBase = 100;
	public int processFluidTime;
	public int progressOre;
	public static final int processOreTimeBase = 1000;
	public int processOreTime;

	public MaterialStack leftStack;
	public MaterialStack rightStack;
	public int maxMaterial = MaterialShapes.BLOCK.q(16);
	
	public FluidTank[] tanks;

	public TileEntityElectrolyser() {
		//0: Battery
		//1-2: Upgrades
		//// FLUID
		//3-4: Fluid ID
		//5-10: Fluid IO
		//11-13: Byproducts
		//// METAL
		//14: Crystal
		//15-20: Outputs
		super(21);
		tanks = new FluidTank[4];
		tanks[0] = new FluidTank(Fluids.WATER, 16000);
		tanks[1] = new FluidTank(Fluids.HYDROGEN, 16000);
		tanks[2] = new FluidTank(Fluids.OXYGEN, 16000);
		tanks[3] = new FluidTank(Fluids.NITRIC_ACID, 16000);
	}
	
	@Override
	public int[] getAccessibleSlotsFromSide(int meta) {
		return new int[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 };
	}

	@Override
	public boolean isItemValidForSlot(int i, ItemStack itemStack) {
		if(i == 14) return ElectrolyserMetalRecipes.getRecipe(itemStack) != null;
		return false;
	}

	@Override
	public boolean canExtractItem(int i, ItemStack itemStack, int j) {
		return i != 14;
	}

	@Override
	public String getName() {
		return "container.machineElectrolyser";
	}

	@Override
	public void updateEntity() {

		if(!worldObj.isRemote) {
			
			this.tanks[0].setType(3, 4, slots);
			this.tanks[0].loadTank(5, 6, slots);
			this.tanks[1].unloadTank(7, 8, slots);
			this.tanks[2].unloadTank(9, 10, slots);
			
			if(worldObj.getTotalWorldTime() % 20 == 0) {
				for(DirPos pos : this.getConPos()) {
					this.trySubscribe(worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());
					this.trySubscribe(tanks[0].getTankType(), worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());
					this.trySubscribe(tanks[3].getTankType(), worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());

					if(tanks[1].getFill() > 0) this.sendFluid(tanks[1], worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());
					if(tanks[2].getFill() > 0) this.sendFluid(tanks[2], worldObj, pos.getX(), pos.getY(), pos.getZ(), pos.getDir());
				}
			}
			
			UpgradeManager.eval(slots, 1, 2);
			int speedLevel = Math.min(UpgradeManager.getLevel(UpgradeType.SPEED), 3);
			int powerLevel = Math.min(UpgradeManager.getLevel(UpgradeType.POWER), 3);

			processFluidTime = processFluidTimeBase - processFluidTimeBase * speedLevel / 4;
			processOreTime = processOreTimeBase - processOreTimeBase * speedLevel / 4;
			usage = usageBase - usageBase * powerLevel / 4;
			
			if(this.canProcessFluid()) {
				this.progressFluid++;
				this.power -= this.usage;
				
				if(this.progressFluid >= this.processFluidTime) {
					this.processFluids();
					this.progressFluid = 0;
					this.markChanged();
				}
			}
			
			NBTTagCompound data = new NBTTagCompound();
			data.setLong("power", this.power);
			data.setInteger("progressFluid", this.progressFluid);
			data.setInteger("progressOre", this.progressOre);
			data.setInteger("usage", this.usage);
			data.setInteger("processFluidTime", this.processFluidTime);
			data.setInteger("processOreTime", this.processOreTime);
			for(int i = 0; i < 4; i++) tanks[i].writeToNBT(data, "t" + i);
			this.networkPack(data, 50);
		}
	}
	
	public DirPos[] getConPos() {
		ForgeDirection dir = ForgeDirection.getOrientation(this.getBlockMetadata() - 10);
		ForgeDirection rot = dir.getRotation(ForgeDirection.UP);
		
		return new DirPos[] {
				new DirPos(xCoord - dir.offsetX * 6, yCoord, zCoord - dir.offsetZ * 6, dir.getOpposite()),
				new DirPos(xCoord - dir.offsetX * 6 + rot.offsetX, yCoord, zCoord - dir.offsetZ * 6 + rot.offsetZ, dir.getOpposite()),
				new DirPos(xCoord - dir.offsetX * 6 - rot.offsetX, yCoord, zCoord - dir.offsetZ * 6 - rot.offsetZ, dir.getOpposite()),
				new DirPos(xCoord + dir.offsetX * 6, yCoord, zCoord + dir.offsetZ * 6, dir),
				new DirPos(xCoord + dir.offsetX * 6 + rot.offsetX, yCoord, zCoord + dir.offsetZ * 6 + rot.offsetZ, dir),
				new DirPos(xCoord + dir.offsetX * 6 - rot.offsetX, yCoord, zCoord + dir.offsetZ * 6 - rot.offsetZ, dir)
		};
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		this.power = nbt.getLong("power");
		this.progressFluid = nbt.getInteger("progressFluid");
		this.progressOre = nbt.getInteger("progressOre");
		this.usage = nbt.getInteger("usage");
		this.processFluidTime = nbt.getInteger("processFluidTime");
		this.processOreTime = nbt.getInteger("processOreTime");
		for(int i = 0; i < 4; i++) tanks[i].readFromNBT(nbt, "t" + i);
	}
	
	public boolean canProcessFluid() {
		
		if(this.power < usage) return false;
		
		ElectrolysisRecipe recipe = ElectrolyserFluidRecipes.recipes.get(tanks[0].getTankType());
		
		if(recipe == null) return false;
		if(recipe.amount > tanks[0].getFill()) return false;
		if(recipe.output1.type == tanks[1].getTankType() && recipe.output1.fill + tanks[1].getFill() > tanks[1].getMaxFill()) return false;
		if(recipe.output2.type == tanks[2].getTankType() && recipe.output2.fill + tanks[2].getFill() > tanks[2].getMaxFill()) return false;
		
		if(recipe.byproduct != null) {
			
			for(int i = 0; i < recipe.byproduct.length; i++) {
				ItemStack slot = slots[11 + i];
				ItemStack byproduct = recipe.byproduct[i];
				
				if(slot == null) continue;
				if(!slot.isItemEqual(byproduct)) return false;
				if(slot.stackSize + byproduct.stackSize > slot.getMaxStackSize()) return false;
			}
		}
		
		return true;
	}
	
	public void processFluids() {
		
		ElectrolysisRecipe recipe = ElectrolyserFluidRecipes.recipes.get(tanks[0].getTankType());
		tanks[0].setFill(tanks[0].getFill() - recipe.amount);
		tanks[1].setTankType(recipe.output1.type);
		tanks[2].setTankType(recipe.output2.type);
		tanks[1].setFill(tanks[1].getFill() + recipe.output1.fill);
		tanks[2].setFill(tanks[2].getFill() + recipe.output2.fill);
		
		if(recipe.byproduct != null) {
			
			for(int i = 0; i < recipe.byproduct.length; i++) {
				ItemStack slot = slots[11 + i];
				ItemStack byproduct = recipe.byproduct[i];
				
				if(slot == null) {
					slots[11 + i] = byproduct.copy();
				} else {
					slots[11 + i].stackSize += byproduct.stackSize;
				}
			}
		}
	}
	
	AxisAlignedBB bb = null;
	
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		
		if(bb == null) {
			bb = AxisAlignedBB.getBoundingBox(
					xCoord - 5,
					yCoord - 0,
					zCoord - 5,
					xCoord + 6,
					yCoord + 4,
					zCoord + 6
					);
		}
		
		return bb;
	}
	
	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}

	@Override
	public long getPower() {
		return this.power;
	}

	@Override
	public long getMaxPower() {
		return maxPower;
	}

	@Override
	public void setPower(long power) {
		this.power = power;
	}

	@Override
	public FluidTank[] getAllTanks() {
		return tanks;
	}

	@Override
	public FluidTank[] getSendingTanks() {
		return new FluidTank[] {tanks[1], tanks[2]};
	}

	@Override
	public FluidTank[] getReceivingTanks() {
		return new FluidTank[] {tanks[0], tanks[3]};
	}

	@Override
	public Container provideContainer(int ID, EntityPlayer player, World world, int x, int y, int z) {
		if(ID == 0) return new ContainerElectrolyserFluid(player.inventory, this);
		return new ContainerElectrolyserMetal(player.inventory, this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public GuiScreen provideGUI(int ID, EntityPlayer player, World world, int x, int y, int z) {
		if(ID == 0) return new GUIElectrolyserFluid(player.inventory, this);
		return new GUIElectrolyserMetal(player.inventory, this);
	}

	@Override
	public void receiveControl(NBTTagCompound data) {
		
	}
	
	@Override
	public void receiveControl(EntityPlayer player, NBTTagCompound data) {

		if(data.hasKey("sgm")) FMLNetworkHandler.openGui(player, MainRegistry.instance, 1, worldObj, xCoord, yCoord, zCoord);
		if(data.hasKey("sgf")) FMLNetworkHandler.openGui(player, MainRegistry.instance, 0, worldObj, xCoord, yCoord, zCoord);
	}

	@Override
	public boolean hasPermission(EntityPlayer player) {
		return this.isUseableByPlayer(player);
	}
}
