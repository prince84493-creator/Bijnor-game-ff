# Pistol animation wiring - STATUS (incomplete)

Done:
- PistolAnim.K 37 -> 40. pistol.anim is baked with 40 floats/frame, so PistolAnim.load() was rejecting the file (k != K).

NOT done yet (nothing below is wired into the game):
- PistolAnim.load(ctx) is never called (add near chars.init() in GameRenderer.onSurfaceCreated).
- CharPose.pa field, skin() baked legs/torso/head + 2-bone IK hands on the gun.
- GameRenderer drawPlayerBody / drawBot: pa.update(...) and set/clear cpose.pa.
- Bots.kt shotAt (set in shootBot and shoot).
- Reach fix: see tools/pistol_anim/retarget.py gun_solve(); 8 iterations with full left weight gave 5 cm worst residual vs 10 cm with the 4-iteration version.
- Prone / swim / jump keep the old animation, blend by decomposing bone world matrices.
- Notes: AIM2 hold chest is yawed 12 deg (AIM1 42 deg) in the pack, so the chest rotation / gun rotation / positions need a yaw correction or the muzzle points off the aim line.
- Bots pass -b.yaw to PistolState (bot yaw sign is opposite of player bodyYaw).
