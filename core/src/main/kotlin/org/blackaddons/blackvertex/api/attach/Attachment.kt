package org.blackaddons.blackvertex.api.attach

/** The player part a cosmetic rides — it inherits that part's pivot and animated rotation. */
enum class FollowPart { NONE, BODY, HEAD, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG }

/**
 * How a preset and its corrections combine in [AttachmentPoint.effective].
 *
 * [ADD] (the default) treats the fields as a delta over the preset — zeros land exactly on
 * the preset; [REPLACE] treats them as absolute values, keeping only the preset's [FollowPart].
 */
enum class AttachMode { ADD, REPLACE }

/**
 * Placement of a cosmetic on the player.
 *
 * Applied in the feature layer's space, which — like vanilla layer transforms — is in
 * **block units** with **+Y pointing DOWN** and **+Z pointing BACK** (behind the player).
 * [follow] first rides a body part (so the cosmetic sways with it); then [offsetX]/[offsetY]/
 * [offsetZ] move it, the rotations orient it, and [scale] resizes it.
 *
 * Prefer a preset from [AttachmentPoint], tweaking with `.copy(...)` when a model needs it.
 */
data class Attachment(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val pitchDeg: Float = 0f, // about X
    val yawDeg: Float = 0f,   // about Y
    val rollDeg: Float = 0f,  // about Z
    val scale: Float = 1f,
    val follow: FollowPart = FollowPart.BODY,
)

/**
 * Known attachment presets. Use `AttachmentPoint.X.attachment`, or `AttachmentPoint.X.effective(...)`
 * to layer per-cosmetic corrections onto it.
 *
 * Offsets are sensible defaults for the Iggy3D pack's origin convention — a different model may still
 * need a small override.
 */
enum class AttachmentPoint(val attachment: Attachment) {
    /** Tail base sitting on the lower back, riding the torso. */
    LOWER_BACK(Attachment(offsetY = 0.4f, offsetZ = 0.04f, follow = FollowPart.BODY)),

    /**
     * Ears on top of the head, riding the head. `pitch 180` maps this pack's Blender axes to
     * model space — flips Y (upright) and Z (faces forward), keeps X (no mirror). Centered on
     * the head; offsetY -0.05 seats them on the crown (calibrated on the Iggy3D cat ears).
     */
    HEAD_TOP(Attachment(offsetY = -0.05f, pitchDeg = 180f, follow = FollowPart.HEAD)),

    /** Bracelet/cuff around the left wrist. Offset reaches the lower third of the arm. */
    LEFT_WRIST(Attachment(offsetY = 0.5f, follow = FollowPart.LEFT_ARM)),

    /** Bracelet/cuff around the right wrist. */
    RIGHT_WRIST(Attachment(offsetY = 0.5f, follow = FollowPart.RIGHT_ARM)),

    /** Left ankle, e.g. leg bands. */
    LEFT_ANKLE(Attachment(offsetY = 0.65f, follow = FollowPart.LEFT_LEG)),

    /** Right ankle. */
    RIGHT_ANKLE(Attachment(offsetY = 0.65f, follow = FollowPart.RIGHT_LEG)),

    /** Flat on the upper back, riding the torso — backpacks, wings, capes' anchor. */
    UPPER_BACK(Attachment(offsetY = 0.15f, offsetZ = 0.14f, follow = FollowPart.BODY)),

    /** Around the waist/belt line, riding the torso. */
    WAIST(Attachment(offsetY = 0.7f, follow = FollowPart.BODY)),

    ;

    /**
     * Combines this preset with per-cosmetic corrections into the final [Attachment].
     *
     * In [AttachMode.ADD] offsets and rotations add component-wise (degrees, before matrices —
     * not a quaternion compose), and [scale] multiplies; in [AttachMode.REPLACE] they are taken
     * as-is. [follow] overrides the preset when non-null, otherwise the preset's is kept.
     *
     * This is the reference math a consuming manifest parser must mirror so a cosmetic placed
     * in an editor lands identically in-game.
     */
    fun effective(
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        offsetZ: Float = 0f,
        pitchDeg: Float = 0f,
        yawDeg: Float = 0f,
        rollDeg: Float = 0f,
        scale: Float = 1f,
        follow: FollowPart? = null,
        mode: AttachMode = AttachMode.ADD,
    ): Attachment = when (mode) {
        AttachMode.ADD -> Attachment(
            offsetX = attachment.offsetX + offsetX,
            offsetY = attachment.offsetY + offsetY,
            offsetZ = attachment.offsetZ + offsetZ,
            pitchDeg = attachment.pitchDeg + pitchDeg,
            yawDeg = attachment.yawDeg + yawDeg,
            rollDeg = attachment.rollDeg + rollDeg,
            scale = attachment.scale * scale,
            follow = follow ?: attachment.follow,
        )
        AttachMode.REPLACE -> Attachment(
            offsetX = offsetX,
            offsetY = offsetY,
            offsetZ = offsetZ,
            pitchDeg = pitchDeg,
            yawDeg = yawDeg,
            rollDeg = rollDeg,
            scale = scale,
            follow = follow ?: attachment.follow,
        )
    }
}
