package com.bijnor.game

import kotlin.math.abs
import kotlin.math.exp

/**
 * Animation state of ONE character (the player or one bot) for the pistol animation pack (PistolAnim).
 * It picks the clip (idle / walk / run / crouch / aim / reload / turn / idle gestures), cross-fades between clips and plays the recoil kick.
 *
 * Usage, once per drawn frame:
 *     state.update(clock, speed, crouch, aimHold, ads, shooting, reloadProgress, bodyYaw, longGun)
 *     then read state.cur (K floats, layout in PistolAnim) - legs / torso / head come from the "lower" channel, gun + arms from the "upper" channel.
 * aimComp (0..1) = how much the upper body is in an aim pose (the renderer keeps the gun level against the torso lean with it).
 * leftMin (0..1) = minimum weight of the left hand staying on the gun (1 for long guns, so a sniper is never carried one-handed).
 */
class PistolState {
    private companion object { const val PACK_SCALE = 1.38f }   // pack metres -> game character units (same as bake_pistol.js)
    /** blended pose of this frame: [0..23] from the lower channel, [24..36] from the upper channel */
    val cur = FloatArray(PistolAnim.K).also { it[3] = 1f; it[7] = 1f; it[11] = 1f; it[15] = 1f; it[19] = 1f; it[23] = 1f; it[27] = 1f }
    var aimComp = 0f; private set
    var leftMin = 0f; private set
    var clipLower = PistolAnim.IDLE; private set
    var clipUpper = PistolAnim.IDLE; private set
    /** 0..1 how much of the baked pose is used by CharModel.skin() (fades to 0 while prone / swimming / jumping, where the old animation takes over) */
    var bakedW = 1f; private set

    /** one cross-fading channel: set(clip, t) every frame, a change of clip starts a fade from the last output */
    private class Chan {
        var clip = -1; var bl = 1f; var fade = 0.25f
        val prev = FloatArray(PistolAnim.K); val out = FloatArray(PistolAnim.K); val tmp = FloatArray(PistolAnim.K)
        fun apply(c: Int, t: Float, dt: Float, fadeDur: Float) {
            if (c != clip) {
                if (clip >= 0) { System.arraycopy(out, 0, prev, 0, PistolAnim.K); bl = 0f } else bl = 1f
                clip = c; fade = if (fadeDur < 0.05f) 0.05f else fadeDur
            }
            PistolAnim.sample(c, t, tmp)
            if (bl < 1f) {
                bl = minOf(1f, bl + dt / fade)
                val s = bl * bl * (3f - 2f * bl)
                PistolAnim.blend(prev, tmp, s, out)
            } else System.arraycopy(tmp, 0, out, 0, PistolAnim.K)
        }
    }
    private val lo = Chan(); private val up = Chan()

    private var lastClock = -1f; private var locoT = 0f
    private var crouched = false
    private var transClip = -1; private var transT = 0f
    private var idleTime = 0f; private var gestIdx = 0; private var gestClip = -1; private var gestT = 0f
    private var lastYaw = 0f; private var yawAcc = 0f; private var turnClip = -1; private var turnT = 0f
    private var aimLatch = 0f
    private var wasShooting = false; private var kickT = -1f; private var kickClip = PistolAnim.KICK2
    private var aimSm = 0f; private var crouchSm = 0f; private var running = false

    fun reset() { running = false; bakedW = 1f; lastClock = -1f; transClip = -1; gestClip = -1; turnClip = -1; kickT = -1f; idleTime = 0f; locoT = 0f; aimSm = 0f; crouchSm = 0f; yawAcc = 0f }

    /**
     * @param clock       game clock (s)
     * @param speed       ground speed (m/s)
     * @param crouch      0..1 crouch amount of the game
     * @param aimHold     gun is up: aiming, firing or just fired
     * @param ads         aiming down the sights (two-hand aim); false + aimHold = hip fire
     * @param shooting    true during the frames a shot / muzzle flash is happening
     * @param reloadProg  0..1 while reloading, < 0 otherwise (drives the reload clip 1:1 with the game's reload time)
     * @param yaw         body yaw (rad) - a quick turn on the spot plays the turn clip
     * @param longGun     shotgun / DMR / sniper / AMR: always two-handed, aims with the two-hand pose
     * @param baked       false while the character is prone / swimming / jumping: the baked pose fades out ([bakedW]) and the old animation fades in
     */
    fun update(clock: Float, speed: Float, crouch: Float, aimHold: Boolean, ads: Boolean, shooting: Boolean, reloadProg: Float, yaw: Float, longGun: Boolean, baked: Boolean = true) {
        if (!PistolAnim.ready) return
        var dt = clock - lastClock
        if (lastClock < 0f || dt <= 0f || dt > 0.25f) { dt = if (lastClock < 0f) 0f else 0.016f; lastYaw = yaw }
        lastClock = clock
        bakedW += ((if (baked) 1f else 0f) - bakedW) * minOf(1f, dt * 10f)
        if (baked && bakedW > 0.995f) bakedW = 1f else if (!baked && bakedW < 0.005f) bakedW = 0f

        // ---- crouch state with hysteresis + transition clips
        val wasCrouched = crouched
        if (!crouched && crouch > 0.55f) crouched = true else if (crouched && crouch < 0.45f) crouched = false
        if (crouched != wasCrouched && lastClock >= 0f && dt > 0f) { transClip = if (crouched) PistolAnim.CROUCH_DOWN else PistolAnim.STAND_UP; transT = 0f; gestClip = -1; turnClip = -1 }
        if (transClip >= 0) {
            transT += dt * 2.0f
            if (transT >= PistolAnim.clipDuration(transClip) || speed > 1.2f) transClip = -1
        }

        val reloading = reloadProg >= 0f
        if (aimHold) aimLatch = 0.6f else if (aimLatch > 0f) aimLatch -= dt
        val gunUp = aimHold || aimLatch > 0f
        val still = speed < 0.25f

        // ---- idle gestures (ready / high ready / lower+look / radio): only after a few seconds of standing still with nothing else going on
        if (gestClip >= 0) {
            gestT += dt
            if (gestT >= PistolAnim.clipDuration(gestClip) || !still || gunUp || reloading || crouched || transClip >= 0) { gestClip = -1; idleTime = 0f }
        } else if (still && !gunUp && !reloading && !crouched && transClip < 0 && turnClip < 0) {
            idleTime += dt
            if (idleTime > 7f) {
                gestClip = when (gestIdx % 4) { 0 -> PistolAnim.READY; 1 -> PistolAnim.HIGH; 2 -> PistolAnim.LOWER; else -> PistolAnim.COMMS }
                gestIdx++; gestT = 0f
            }
        } else idleTime = 0f

        // ---- turn on the spot
        var d = yaw - lastYaw; lastYaw = yaw
        while (d > Math.PI) d -= (2.0 * Math.PI).toFloat(); while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
        yawAcc = yawAcc * exp(-dt * 4f) + d
        if (turnClip >= 0) {
            turnT += dt * 1.9f
            if (turnT >= PistolAnim.clipDuration(turnClip) || !still || gunUp || reloading || crouched) turnClip = -1
        } else if (still && !gunUp && !reloading && !crouched && transClip < 0 && gestClip < 0 && abs(yawAcc) > 0.6f) {
            turnClip = if (yawAcc > 0f) PistolAnim.TURN_R else PistolAnim.TURN_L; turnT = 0f; yawAcc = 0f; idleTime = 0f
        }

        // ---- locomotion: the playback rate is set from the REAL ground speed of the clip (pack speed x pack->game scale x character scale), so the feet do not slide.
        // The game moves much faster than the pack (walk 6 m/s, sprint 10), so the rate is capped to keep the legs believable.
        val gs = PACK_SCALE * CharRig.SCALE
        if (running) { if (speed < 1.9f) running = false } else if (speed > 2.3f) running = true
        val loco: Int; val rate: Float
        if (crouched) {
            if (still) { loco = PistolAnim.CROUCH_IDLE; rate = 1f } else { loco = PistolAnim.CROUCH_WALK; rate = (speed / (PistolAnim.clipSpeed(PistolAnim.CROUCH_WALK) * gs)).coerceIn(0.7f, 2.4f) }
        } else if (still) { loco = PistolAnim.IDLE; rate = 1f }
        else if (!running) { loco = PistolAnim.WALK; rate = (speed / (PistolAnim.clipSpeed(PistolAnim.WALK) * gs)).coerceIn(0.6f, 1.8f) }
        else { loco = PistolAnim.RUN; rate = (speed / (PistolAnim.clipSpeed(PistolAnim.RUN) * gs)).coerceIn(0.7f, 2.2f) }
        locoT += dt * rate

        // ---- choose the clip of each channel
        var lc = loco; var lt = locoT; var uc = loco; var ut = locoT; var fade = 0.25f
        if (reloading) {
            val rc = if (crouched) PistolAnim.CROUCH_RELOAD else PistolAnim.RELOAD
            lc = rc; uc = rc; lt = reloadProg.coerceIn(0f, 1f) * PistolAnim.clipDuration(rc); ut = lt; fade = 0.2f
        } else if (transClip >= 0) { lc = transClip; uc = transClip; lt = transT; ut = transT }
        else if (turnClip >= 0) { lc = turnClip; uc = turnClip; lt = turnT; ut = turnT }
        else if (gestClip >= 0) { lc = gestClip; uc = gestClip; lt = gestT; ut = gestT }
        val aimUp = gunUp && !reloading && transClip < 0 && gestClip < 0
        var aimTarget = 0f
        if (aimUp) {
            val oneHand = !ads && !longGun
            uc = if (oneHand) PistolAnim.AIM1 else PistolAnim.AIM2
            ut = if (oneHand) PistolAnim.AIM1_HOLD else PistolAnim.AIM2_HOLD
            aimTarget = 1f
            // recoil: a new shot restarts the kick (but not faster than every 0.12 s, so full-auto does not jitter)
            if (shooting && !wasShooting && (kickT < 0f || kickT > 0.12f)) { kickT = 0f; kickClip = if (oneHand) PistolAnim.KICK1 else PistolAnim.KICK2 }
        }
        wasShooting = shooting
        if (kickT >= 0f) {
            kickT += dt * 1.6f
            if (kickT >= PistolAnim.clipDuration(kickClip) || !aimUp) kickT = -1f
            else { uc = kickClip; ut = kickT; fade = 0.06f }
        }

        lo.apply(lc, lt, dt, fade); up.apply(uc, ut, dt, if (uc == PistolAnim.KICK1 || uc == PistolAnim.KICK2) 0.06f else fade)
        clipLower = lc; clipUpper = uc
        System.arraycopy(lo.out, 0, cur, 0, 24)
        System.arraycopy(up.out, 24, cur, 24, PistolAnim.K - 24)
        // crouch + aim: the gun / arm channel (AIM1 / AIM2) is expressed relative to the STANDING aim chest, the crouch clips have a different (leaning) chest.
        // With the crouch chest the aim-line correction in PistolRig had to compensate a large pitch / yaw mismatch (clamped -> the muzzle drifted 16-30 deg off the aim line),
        // so while aiming in a crouch the chest of the aim clip is blended in (weight = aim x crouch, both smoothed). Legs stay in the crouch clip.
        crouchSm += ((if (crouched) 1f else 0f) - crouchSm) * minOf(1f, dt * 8f)
        val cw = aimSm * crouchSm
        if (cw > 0.001f) {
            val lq = lo.out; val uq = up.out
            val sg = if (lq[0] * uq[0] + lq[1] * uq[1] + lq[2] * uq[2] + lq[3] * uq[3] < 0f) -1f else 1f
            var x = lq[0] + (uq[0] * sg - lq[0]) * cw; var y = lq[1] + (uq[1] * sg - lq[1]) * cw
            var z = lq[2] + (uq[2] * sg - lq[2]) * cw; var w = lq[3] + (uq[3] * sg - lq[3]) * cw
            val l = Math.sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
            if (l > 1e-6f) { cur[0] = x / l; cur[1] = y / l; cur[2] = z / l; cur[3] = w / l }
        }

        aimSm += (aimTarget - aimSm) * minOf(1f, dt * 8f); aimComp = aimSm
        leftMin = if (longGun && gestClip != PistolAnim.COMMS) 1f else 0f
    }
}
