package pixelmon.battles.controller;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import pixelmon.RandomHelper;
import pixelmon.battles.BattleRegistry;
import pixelmon.battles.attacks.Attack;
import pixelmon.battles.attacks.attackEffects.EffectBase;
import pixelmon.battles.attacks.attackEffects.EffectBase.ApplyStage;
import pixelmon.battles.attacks.attackModifiers.PriorityAttackModifier;
import pixelmon.battles.attacks.statusEffects.StatusEffectBase;
import pixelmon.battles.participants.BattleParticipant;
import pixelmon.battles.participants.ParticipantType;
import pixelmon.battles.participants.PlayerParticipant;
import pixelmon.comm.ChatHandler;
import pixelmon.config.PixelmonItems;
import pixelmon.entities.pixelmon.EntityPixelmon;
import pixelmon.enums.heldItems.EnumHeldItems;
import pixelmon.items.ItemHeld;
import pixelmon.items.PixelmonItem;
import pixelmon.storage.PixelmonStorage;
import pixelmon.storage.PlayerStorage;
import cpw.mods.fml.common.network.Player;

public class BattleController {

	public int battleIndex;

	public ArrayList<BattleParticipant> participants = new ArrayList<BattleParticipant>();

	private int battleTicks = 0;

	public ArrayList<StatusEffectBase> battleStatusList = new ArrayList<StatusEffectBase>();
	public boolean battleEnded = false;
	public int turnCount = 0;

	public BattleController(BattleParticipant participant1, BattleParticipant participant2) throws Exception {
		participant1.startedBattle = true;
		participant1.team = 0;
		participant2.team = 1;
		participants.add(participant1);
		participants.add(participant2);
		initBattle();
	}

	private void initBattle() throws Exception {
		for (BattleParticipant p : participants) {
			if (!p.checkPokemon()){
				throw new Exception("Battle Could not start!");
			}
		}
		BattleRegistry.registerBattle(this);
		for (BattleParticipant p : participants) {
			p.StartBattle(this, otherParticipant(p));
			p.updateOpponent();
			if (p.canGainXP())
				p.addToAttackersList();
		}
	}

	BattleParticipant otherParticipant(BattleParticipant current) {
		for (BattleParticipant p : participants)
			if (p != current)
				return p;
		return null;
	}

	enum MoveStage {
		PickAttacks, Move
	};

	private MoveStage moveStage = MoveStage.PickAttacks;

	// private boolean pixelmon1MovesFirst = true;
	// private Attack[] attacks = new Attack[2];

	public void endBattle() {
		battleEnded = true;
		for (BattleParticipant p : participants)
			p.EndBattle();
		BattleRegistry.deRegisterBattle(this);
	}

	public void endBattleWithoutXP() {
		battleEnded = true;
		BattleRegistry.deRegisterBattle(this);
		for (BattleParticipant p : participants)
			p.EndBattle();
	}

	int turn = 0;

	public void update() {
		if (isWaiting() || paused)
			return;
		int tickTop;
		if (moveStage == MoveStage.PickAttacks)
			tickTop = 30;
		else
			tickTop = 70;
		if (battleTicks++ > tickTop) {

			if (moveStage == MoveStage.PickAttacks) { // Pick Moves
				// moveToPositions();
				PickingMoves.pickMoves(this);
				PickingMoves.checkMoveSpeed(this);
				moveStage = MoveStage.Move;
				turn = 0;
			} else if (moveStage == MoveStage.Move) { // First Move
				takeTurn(participants.get(turn));
				turn++;

				if (turn == participants.size()) {
					moveStage = MoveStage.PickAttacks;
					for (BattleParticipant p : participants) {
						p.turnTick();
					}
					for (int i = 0; i < battleStatusList.size(); i++) {
						try {
							battleStatusList.get(i).turnTick(null, null);
						} catch (Exception e) {
							System.out.println("Error on battleStatus tick for " + battleStatusList.get(i).type.toString());
							e.printStackTrace();
						}
					}
					turnCount++;
				}
				checkAndReplaceFaintedPokemon();
			}
			battleTicks = 0;
		}
	}

	private void checkAndReplaceFaintedPokemon() {
		for (BattleParticipant p : participants) {
			p.updatePokemon();
			if (p.getIsFaintedOrDead()) {
				String name = p.currentPokemon().getNickname().equals("") ? p.currentPokemon().getName() : p.currentPokemon().getNickname();
				sendToOtherParticipants(p, p.getFaintMessage());
				if (p.getType() == ParticipantType.Player)
					ChatHandler.sendChat(p.currentPokemon().getOwner(), "Your " + name + " fainted!");
				Experience.awardExp(participants, p, p.currentPokemon());

				p.currentPokemon().setEntityHealth(0);
				p.currentPokemon().setDead();
				p.currentPokemon().isFainted = true;
				p.updatePokemon();

				if (p.hasMorePokemon()) {
					p.wait = true;
					p.getNextPokemon();
				} else {
					endBattle();
				}
			}
		}
	}

	public void sendToOtherParticipants(BattleParticipant current, String string) {
		for (BattleParticipant p : participants)
			if (p != current)
				ChatHandler.sendBattleMessage(p.currentPokemon().getOwner(), string);
	}

	public void setAttack(EntityPixelmon mypixelmon, Attack a) {
		for (BattleParticipant p : participants)
			if (p.currentPokemon() == mypixelmon) {
				p.attack = a;
				p.wait = false;
				p.attackList.add(a.baseAttack.attackName);
				return;
			}
	}

	private void takeTurn(BattleParticipant p) {
		if (p.willTryFlee && !p.currentPokemon().isLockedInBattle) {
			calculateEscape(p, p.currentPokemon(), otherParticipant(p).currentPokemon());
		} else if (p.currentPokemon().isLockedInBattle)
			ChatHandler.sendBattleMessage(p.currentPokemon().getOwner(), "Cannot escape!");
		else if (p.isSwitching)
			p.isSwitching = false;
		else if (p.willUseItemInStack != null)
			useItem(p);
		else
			p.attack.use(p.currentPokemon(), otherParticipant(p).currentPokemon(), p.attackList, otherParticipant(p).attackList);
	}

	private void calculateEscape(BattleParticipant p, EntityPixelmon user, EntityPixelmon target) {

		ChatHandler.sendChat(user.getOwner(), target.getOwner(), user.getName() + " tries to run away");
		float A = ((float) user.stats.Speed) * ((float) user.battleStats.getSpeedModifier()) / 100;
		float B = ((float) target.stats.Speed) * ((float) target.battleStats.getSpeedModifier()) / 100;
		if (B > 255)
			B = 255;
		float C = p.escapeAttempts++;
		float F = A * 32 / B + 30 * C;

		if (F > 255 || new Random().nextInt(255) < F) {
			if (!user.isLockedInBattle) {
				ChatHandler.sendBattleMessage(user.getOwner(), target.getOwner(), "Running can escape");
				ChatHandler.sendBattleMessage(user.getOwner(), target.getOwner(), user.getName() + " escaped!");
				endBattle();
			} else {
				ChatHandler.sendBattleMessage(user.getOwner(), target.getOwner(), "Its locked in battle!");
			}
		} else
			ChatHandler.sendBattleMessage(user.getOwner(), target.getOwner(), user.getName() + " couldn't escape!");
	}

	
	public void setFlee(EntityPixelmon mypixelmon) {
		for (BattleParticipant p : participants)
			if (mypixelmon == p.currentPokemon()) {
				p.willTryFlee = true;
				p.wait = false;
			}
	}

	public void setUseItem(Player user, ItemStack usedStack, int additionalInfo) {
		for (BattleParticipant p : participants)
			if (p.getType() == ParticipantType.Player && p.getEntity() == user) {
				p.willUseItemInStack = usedStack;
				p.willUseItemInStackInfo = additionalInfo;
				p.wait = false;
			}
	}

	public void SwitchPokemon(EntityPixelmon currentPixelmon, int newPixelmonId) {
		for (BattleParticipant p : participants)
			if (p.currentPokemon() == currentPixelmon) {
				p.switchPokemon(newPixelmonId);
				p.currentPokemon().battleController = this;
				p.attackersList.add(p.currentPokemon().getPokemonId());
				p.isSwitching = true;
				p.wait = false;
				for (BattleParticipant p2 : participants)
					if (p2.team != p.team) {
						p2.attackersList.clear();
						p2.attackersList.add(p2.currentPokemon().getPokemonId());
						p2.updateOpponent();
					}

			}
	}

	public void useItem(BattleParticipant p) {
		EntityPixelmon userPokemon = null, targetPokemon = null;
		PixelmonItem item = null;
		EntityPlayer user = null;
		ItemStack usedStack = null;
		int additionalInfo = 0;
		userPokemon = p.currentPokemon();
		targetPokemon = otherParticipant(p).currentPokemon();
		usedStack = p.willUseItemInStack;
		additionalInfo = p.willUseItemInStackInfo;
		user = ((PlayerParticipant) p).player;
		p.willUseItemInStack = null;
		p.willUseItemInStackInfo = 0;

		item = (PixelmonItem) usedStack.getItem();
		item.useFromBag(userPokemon, targetPokemon, additionalInfo);

		ItemStack[] inv = user.inventory.mainInventory;
		item.removeFromInventory(inv);
	}

	public boolean isTrainerVsTrainer() {
		return false;
	}

	boolean paused = false;

	public void pauseBattle() {
		paused = true;
	}

	public boolean isWaiting() {
		for (BattleParticipant p : participants)
			if (p.wait)
				return true;
		return false;
	}

	public void endPause() {
		paused = false;
	}
	
}
