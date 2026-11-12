package dev.soloton.roadwork

import dev.soloton.api.SettingsStore
import kotlin.math.roundToInt

/** The units distances, speeds and paces are shown in. */
enum class Units(val id: String, val title: String, val short: String, val meters: Double) {
    KILOMETRES("km", "Kilometres", "km", 1000.0),
    MILES("miles", "Miles", "mi", 1609.344);

    companion object {
        fun byId(id: String?): Units = entries.firstOrNull { it.id == id } ?: KILOMETRES
    }
}

/** What a ride is aiming for. */
enum class GoalKind(val id: String, val title: String) {
    FREE("free", "Free ride"),
    DISTANCE("distance", "Distance"),
    TIME("time", "Time");

    companion object {
        fun byId(id: String?): GoalKind = entries.firstOrNull { it.id == id } ?: FREE
    }
}

/**
 * One thing about an environment the rider can choose, from a short list: for example which
 * aircraft Pedalcraft Island flies. The first choice is the default.
 *
 * @param key saved in settings and passed to the scene, so never rename it
 * @param note a line of small print under the control, or null
 */
class EnvironmentOption(val key: String, val title: String, val choices: List<Choice>, val note: String? = null) {
    class Choice(val id: String, val title: String)

    init {
        require(key.isNotBlank() && choices.isNotEmpty()) { "option '$key' needs a key and at least one choice" }
        require(choices.map { it.id }.toSet().size == choices.size) { "option '$key' has two choices with the same id" }
    }

    val default: Choice get() = choices.first()

    /** The choice with this id, or the default if there is none (a saved id of a choice since removed). */
    fun choice(id: String?): Choice = choices.firstOrNull { it.id == id } ?: default
}

/**
 * The settings of one environment: its units, its goal and its options. Immutable; the `with...`
 * functions return a changed copy.
 *
 * @param goalDistance the distance goal in whole [units] (10 means 10 km, or 10 miles)
 * @param goalMinutes the time goal
 * @param options the chosen [EnvironmentOption.Choice] id for each option key
 * @param speedScale how many metres of world go by for each metre the bike covers, or null to use
 *   the environment's own ([Environment.speedScale])
 */
class EnvironmentSettings(
    val units: Units = Units.KILOMETRES,
    val goalKind: GoalKind = GoalKind.FREE,
    val goalDistance: Int = DEFAULT_DISTANCE,
    val goalMinutes: Int = DEFAULT_MINUTES,
    val options: Map<String, String> = emptyMap(),
    val speedScale: Float? = null,
) {
    fun withUnits(value: Units) = EnvironmentSettings(value, goalKind, goalDistance, goalMinutes, options, speedScale)

    fun withGoalKind(value: GoalKind) = EnvironmentSettings(units, value, goalDistance, goalMinutes, options, speedScale)

    /** One step up or down the list of distances or times, whichever the goal is; unchanged for a free ride. */
    fun withGoalStepped(up: Boolean): EnvironmentSettings = when (goalKind) {
        GoalKind.DISTANCE -> EnvironmentSettings(units, goalKind, step(DISTANCES, goalDistance, up), goalMinutes, options, speedScale)
        GoalKind.TIME -> EnvironmentSettings(units, goalKind, goalDistance, step(MINUTES, goalMinutes, up), options, speedScale)
        GoalKind.FREE -> this
    }

    fun withOption(key: String, choiceId: String) =
        EnvironmentSettings(units, goalKind, goalDistance, goalMinutes, options + (key to choiceId), speedScale)

    /** [value] as this environment's speed, or null to go back to the environment's own. */
    fun withSpeedScale(value: Float?) = EnvironmentSettings(units, goalKind, goalDistance, goalMinutes, options, value)

    /** The speed in force in [environment]: the rider's if they have set one, otherwise the environment's own. */
    fun speedScaleFor(environment: Environment): Float = speedScale ?: environment.speedScale

    /**
     * One step of [SPEED_STEP] up or down from the speed in force in [environment], landing on a
     * multiple of the step and staying within what a ride allows.
     */
    fun withSpeedStepped(up: Boolean, environment: Environment): EnvironmentSettings {
        val steps = Math.round(speedScaleFor(environment) / SPEED_STEP) + if (up) 1 else -1
        return withSpeedScale((steps * SPEED_STEP).coerceIn(RideState.MIN_WORLD_SCALE, RideState.MAX_WORLD_SCALE))
    }

    /** The goal's distance in metres, or 0 if the goal is not a distance. */
    val goalMeters: Double get() = if (goalKind == GoalKind.DISTANCE) goalDistance * units.meters else 0.0

    /** The goal's time in seconds, or 0 if the goal is not a time. */
    val goalSeconds: Double get() = if (goalKind == GoalKind.TIME) goalMinutes * 60.0 else 0.0

    /** How far through the goal a ride is, 0 to 1; 0 for a free ride. */
    fun progress(elapsedSeconds: Double, distanceMeters: Double): Float = when (goalKind) {
        GoalKind.DISTANCE -> (distanceMeters / goalMeters).toFloat().coerceIn(0f, 1f)
        GoalKind.TIME -> (elapsedSeconds / goalSeconds).toFloat().coerceIn(0f, 1f)
        GoalKind.FREE -> 0f
    }

    /** The goal in words: "10 km", "30 min", or "" for a free ride. */
    fun goalText(): String = when (goalKind) {
        GoalKind.DISTANCE -> "$goalDistance ${units.short}"
        GoalKind.TIME -> "$goalMinutes min"
        GoalKind.FREE -> ""
    }

    override fun equals(other: Any?): Boolean = other is EnvironmentSettings && units == other.units && goalKind == other.goalKind &&
        goalDistance == other.goalDistance && goalMinutes == other.goalMinutes && options == other.options && speedScale == other.speedScale

    override fun hashCode(): Int = arrayOf(units, goalKind, goalDistance, goalMinutes, options, speedScale).contentHashCode()

    companion object {
        /** The distances a goal can be set to, in whole kilometres or miles, and the times, in minutes. */
        val DISTANCES = intArrayOf(1, 2, 3, 5, 8, 10, 15, 20, 25, 30, 40, 50, 75, 100)
        val MINUTES = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
        const val DEFAULT_DISTANCE = 10
        const val DEFAULT_MINUTES = 30

        /** How much one press of the speed's minus or plus changes it. */
        const val SPEED_STEP = 0.25f

        /** The next value up or down [values] from [current] (which need not be in it), stopping at the ends. */
        internal fun step(values: IntArray, current: Int, up: Boolean): Int =
            if (up) values.firstOrNull { it > current } ?: values.last() else values.lastOrNull { it < current } ?: values.first()
    }
}

/**
 * Loads and saves each environment's [EnvironmentSettings] in a [SettingsStore], under keys that
 * begin with the environment's id. Anything missing or no longer valid falls back to its default,
 * so a saved setting never stops an environment from opening.
 */
class EnvironmentSettingsStore(private val store: SettingsStore) {
    fun load(environment: Environment): EnvironmentSettings {
        val prefix = "env.${environment.id}."
        val options = HashMap<String, String>()
        for (option in environment.options) options[option.key] = option.choice(store.get(prefix + "option." + option.key)).id
        return EnvironmentSettings(
            units = Units.byId(store.get(prefix + "units")),
            goalKind = GoalKind.byId(store.get(prefix + "goal")),
            goalDistance = store.get(prefix + "goalDistance")?.toIntOrNull()?.takeIf { it in 1..1000 } ?: EnvironmentSettings.DEFAULT_DISTANCE,
            goalMinutes = store.get(prefix + "goalMinutes")?.toIntOrNull()?.takeIf { it in 1..1440 } ?: EnvironmentSettings.DEFAULT_MINUTES,
            options = options,
            speedScale = store.get(prefix + "speed")?.toFloatOrNull()
                ?.takeIf { it in RideState.MIN_WORLD_SCALE..RideState.MAX_WORLD_SCALE },
        )
    }

    fun save(environment: Environment, settings: EnvironmentSettings) {
        val prefix = "env.${environment.id}."
        store.put(prefix + "units", settings.units.id)
        store.put(prefix + "goal", settings.goalKind.id)
        store.put(prefix + "goalDistance", settings.goalDistance.toString())
        store.put(prefix + "goalMinutes", settings.goalMinutes.toString())
        for (option in environment.options) store.put(prefix + "option." + option.key, option.choice(settings.options[option.key]).id)
        // An empty value means "the environment's own": it does not parse as a number, so load() gives null.
        store.put(prefix + "speed", settings.speedScale?.toString() ?: "")
    }
}

/**
 * The numbers on the HUD as text, in the rider's [Units]. Each function clears [sb] and writes
 * into it, so the HUD can format every frame without allocating.
 */
object RideText {
    /** A distance to two decimals with its unit: "2.04 km", "1.27 mi". */
    fun distance(sb: StringBuilder, meters: Double, units: Units) {
        sb.setLength(0)
        val hundredths = hundredths(meters, units)
        sb.append(hundredths / 100).append('.')
        pad2(sb, hundredths % 100)
        sb.append(' ').append(units.short)
    }

    /** A distance in hundredths of a unit, rounded down, so the display never runs ahead of the ride. */
    fun hundredths(meters: Double, units: Units): Long = (meters / units.meters * 100.0).toLong()

    /** A speed to one decimal: "10.8 km/h", "6.7 mph". */
    fun speed(sb: StringBuilder, metersPerSecond: Float, units: Units) {
        sb.setLength(0)
        val tenths = (metersPerSecond * 3600.0 / units.meters * 10.0).roundToInt()
        sb.append(tenths / 10).append('.').append(tenths % 10).append(if (units == Units.MILES) " mph" else " km/h")
    }

    /** Time per unit of distance: "5:33 /km", "8:56 /mi", or dashes when stopped. */
    fun pace(sb: StringBuilder, metersPerSecond: Float, units: Units) {
        sb.setLength(0)
        if (metersPerSecond < 0.05f) {
            sb.append("--:--")
        } else {
            val seconds = (units.meters / metersPerSecond).roundToInt()
            sb.append(seconds / 60).append(':')
            pad2(sb, (seconds % 60).toLong())
        }
        sb.append(" /").append(units.short)
    }

    /** Elapsed time: "12:34", or "1:02:03" from an hour on. */
    fun time(sb: StringBuilder, seconds: Long) {
        sb.setLength(0)
        val h = seconds / 3600
        val m = (seconds / 60) % 60
        if (h > 0) {
            sb.append(h).append(':')
            pad2(sb, m)
        } else {
            sb.append(m)
        }
        sb.append(':')
        pad2(sb, seconds % 60)
    }

    /** A speed scale to two decimals: "x1.50", "x2.25". */
    fun scale(sb: StringBuilder, value: Float) {
        sb.setLength(0)
        val hundredths = Math.round(value * 100f).toLong()
        sb.append('x').append(hundredths / 100).append('.')
        pad2(sb, hundredths % 100)
    }

    private fun pad2(sb: StringBuilder, v: Long) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }
}
