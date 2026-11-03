package net.ptcrys.fpsmatch.editor.common.model.map;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The complete, immutable candidate definition of one map (fixed task card R05.3, revision R1.1).
 *
 * <p>
 * This record is the aggregate cross reference point of the world editor map model. It binds the
 * two typed identities, the mode, the single dimension, the display text, the boundary box and the
 * four owned collections of teams, spawns, regions and resource references, plus the frozen rule and
 * extension carriers and the map level wire extras.
 * </p>
 *
 * <p>
 * <strong>The type is a candidate definition, nothing more.</strong> It deliberately does not
 * create a running map, a repository, an instance, a world reference cache or a provider default. It
 * never reads a world, a save, a registry, a resource library or a live team, it never allocates an
 * identity, and it never touches a file or a network location. Every identity and every reference is
 * supplied by the caller.
 * </p>
 *
 * <p>
 * <strong>Null versus JSON null.</strong> Every member is required to be a non-null Java
 * reference, and so is every element of the four lists; a null argument is rejected with
 * {@link IllegalArgumentException} and never with a {@code NullPointerException}. That is
 * deliberately different from a JSON {@code null} <em>inside</em> a frozen {@code rules},
 * {@code extensions}, {@code config} or extra member: such a value is legal frozen JSON and is
 * carried verbatim. No member is defaulted, no empty array, empty object or empty name is
 * substituted for a missing value, and no member is repaired.
 * </p>
 *
 * <p>
 * <strong>Why the sizes are checked before anything is copied.</strong> The four collections have
 * hard budgets: at most 64 teams, 16384 spawns, 1024 regions and 1024 resource references. A caller
 * may hand in any {@link List}, including a hostile or lazy implementation that reports
 * {@link Integer#MAX_VALUE} as its {@code size()} and throws as soon as an element is accessed, or
 * one whose {@code toArray()} would try to allocate an array of the reported size. This constructor
 * therefore reads {@code size()} first and rejects a value that is negative or above the budget
 * <em>before</em> any {@code copy}, {@code toArray()}, iterator or per element allocation happens, so
 * a rejection costs no memory proportional to a lying size. Only after the size is known to be inside
 * the budget is the list traversed - at most {@code limit + 1} elements - into a fresh local list.
 * If the traversal yields fewer or more elements than the reported size, or yields a null element,
 * the input is rejected as well. A concurrent modification of the caller's list or a throwing
 * iterator is not silently repaired: no {@code Throwable} or {@code Error} is caught anywhere.
 * </p>
 *
 * <p>
 * <strong>Identity domains.</strong> Team identity uniqueness is checked with a local
 * {@link HashSet} keyed on {@link Ids.TeamId}. Spawns and regions share one
 * {@link Ids.ObjectId} domain, so a repeated object identity is rejected whether both occurrences are
 * spawns, both are regions, or one is each. Distinct typed identity kinds never collide even when
 * their UUIDs are equal, so a {@code TeamId} and an {@code ObjectId} carrying the same UUID are both
 * legal. Duplicated names, coordinates, corners and configuration values are legal and their input
 * order is preserved exactly: this type never deduplicates, sorts or reorders any collection.
 * </p>
 *
 * <p>
 * <strong>References are resolved, never guessed.</strong> Every {@code spawn.teamId} must exist
 * in this definition's own team set; the check is completed for the whole team list before any spawn
 * is examined, so the order of the teams does not decide whether a reference is found. A dangling
 * reference is rejected instead of silently creating a team, moving the spawn to the first team or
 * deleting the spawn. Every {@code spawn.dimensionId} must equal the definition's dimension, because
 * one definition is bound to exactly one dimension and a cross dimension spawn is structurally
 * invalid. Business requirements are deliberately not checked here: team sizes, a minimum number of
 * spawns per team and a requirement that a map has teams or regions are provider or UI concerns, so
 * four empty lists are a legal definition.
 * </p>
 *
 * <p>
 * <strong>Resource references are values, not owned objects.</strong> A
 * {@link ResourceRevisionRef} is a reference to a specific revision of an external resource and is
 * <em>not</em> an {@link Ids.ObjectId} owned by this map, so it takes no part in the object identity
 * domain. Two completely identical references, and two references that share a UUID but carry a
 * different legal kind or revision, are all retained in their original order. This type never
 * collapses repeated references by UUID into a "latest" revision, never checks that a resource
 * exists, never inspects its content and never computes a digest; {@link ResourceRevisionRef} has
 * already validated the reference format.
 * </p>
 *
 * <p>
 * <strong>Frozen JSON is carried, not reinterpreted.</strong> {@code rules} and
 * {@code extensions} and every {@code config}, {@code extensions} and extra member of an owned team,
 * spawn or region are already deeply immutable {@link ExactJson.FrozenJson} values and may hold any
 * legal frozen JSON shape. They are reused by reference: this constructor never deep copies,
 * re-parses, re-freezes or normalises them.
 * </p>
 *
 * <p>
 * <strong>The payload lower bound and what it does not prove.</strong> While copying, the
 * constructor accumulates a {@code long} lower bound over the known payload: the full
 * {@link ExactJson.FrozenJson#utf8Size()} of every known carrier, and
 * {@code utf8Size() - 2} of every member of every extra component. The {@code - 2} is what makes the
 * bound honest: the container braces of an extras component are not written to the formal wire as a
 * separate {@code {}} object, its members are appended into the owning object instead, so an empty
 * extras object contributes exactly zero. Every <em>occurrence</em> counts once, so a shared legal
 * instance used twice is charged twice and is never deduplicated by identity. The total is
 * accumulated with {@code long} arithmetic and the check runs <em>before</em> the corresponding item
 * is added to the local list, so a rejection never publishes a partially built definition. An
 * accepted candidate therefore has a payload lower bound of at most
 * {@link ExactJson#MAX_UTF8_BYTES} bytes.
 * </p>
 *
 * <p>
 * This bound is explicitly <em>not</em> a proof of the complete payload budget. Field names, array
 * punctuation, escaped name text, the total node count and the nesting depth all lie outside it and
 * belong to the single formal payload codec of R06.1, which computes and guards the complete wire
 * budget before allocating any encoder member and only then freezes the final result through
 * {@link ExactJson}. This constructor never calls a codec, and the codec must not call back into this
 * constructor to compute wire size, so no circular dependency and no second copy of the payload JSON
 * algorithm exists. A structurally accepted candidate whose lower bound fits may still exceed the
 * complete wire budget: the formal encoder must reject that, and such a candidate must not be
 * committed. The structural lower bound is recorded here; the complete payload budget is registered
 * by P06.
 * </p>
 *
 * <p>
 * <strong>Order preservation.</strong> The {@code boundary} box keeps the {@code first} and
 * {@code second} corner order it was given, including a zero or reversed span and a saturated extreme
 * volume. The real world border and height are never consulted, so no coordinate is clamped and no
 * span is rejected for being implausible.
 * </p>
 *
 * @param id          the definition identity, never null and never generated here
 * @param worldId     the owning world identity, never null and never derived from a save name
 * @param modeId      explicit {@code namespace:path} game mode; an unknown but legal mode is kept
 * @param dimensionId explicit {@code namespace:path} dimension this whole definition is bound to
 * @param displayName the map display name, at most 512 UTF-8 bytes; the empty string is legal
 * @param description the map description, at most 8192 UTF-8 bytes, newlines kept verbatim
 * @param boundary    the boundary box, with the supplied corner order preserved
 * @param teams       the teams, at most 64, unique by {@link Ids.TeamId}, order preserved
 * @param spawns      the spawn points, at most 16384, unique by object identity, every {@code teamId} of
 *                    this definition and every {@code dimensionId} equal to {@code dimensionId}
 * @param regions     the regions, at most 1024, sharing the object identity domain with the spawns
 * @param rules       frozen rule payload, any legal frozen JSON shape, reused by reference
 * @param resources   the resource references, at most 1024, repeated occurrences preserved in order
 * @param extensions  frozen unknown members, any legal frozen JSON shape, reused by reference
 * @param wireExtras  unknown members of the map, boundary, first corner and second corner wire objects
 * @throws IllegalArgumentException if any member or list element is null, if any scalar is illegal,
 *                                  if a size is negative or above its budget, if a traversal disagrees with the
 *                                  reported size, if
 *                                  an identity is repeated inside its domain, if a spawn references an unknown team or
 *                                  another
 *                                  dimension, or if the payload lower bound exceeds the exact JSON byte budget
 */
public record MapDefinition(
                            Ids.DefinitionId id, Ids.WorldId worldId, String modeId,
                            String dimensionId, String displayName, String description, BoxCorners boundary,
                            List<TeamDefinition> teams, List<SpawnDefinition> spawns,
                            List<RegionDefinition> regions, ExactJson.FrozenJson rules,
                            List<ResourceRevisionRef> resources, ExactJson.FrozenJson extensions,
                            WireExtras.MapExtras wireExtras) {

    /** Maximum number of teams a single map definition may own. */
    private static final int MAX_TEAMS = 64;

    /** Maximum number of spawn points a single map definition may own. */
    private static final int MAX_SPAWNS = 16384;

    /** Maximum number of regions a single map definition may own. */
    private static final int MAX_REGIONS = 1024;

    /** Maximum number of resource references a single map definition may own. */
    private static final int MAX_RESOURCES = 1024;

    /**
     * UTF-8 bytes that the container braces of one extras component occupy in the frozen canonical
     * text but not in the formal wire, where the members are appended into the owning object.
     */
    private static final int EXTRAS_CONTAINER_UTF8_BYTES = 2;

    /**
     * Rejects every null, out of budget or cross referencing member and publishes only fully built
     * local copies.
     *
     * <p>
     * The order of validation is fixed and observable: all references and scalars first, then the
     * four collection sizes as a guard before any traversal, then the bounded copy of each
     * collection together with its identity, reference, dimension and payload lower bound checks.
     * Nothing is published until every check has passed, and no global state, no counter and no
     * identity is created on the failure path.
     * </p>
     */
    public MapDefinition {
        if (id == null) {
            throw new IllegalArgumentException("MapDefinition.id must not be null");
        }
        if (worldId == null) {
            throw new IllegalArgumentException("MapDefinition.worldId must not be null");
        }
        modeId = MapScalars.requireNamespaced(modeId);
        dimensionId = MapScalars.requireDimension(dimensionId);
        displayName = MapScalars.requireName(displayName, false);
        description = MapScalars.requireDescription(description);
        if (boundary == null) {
            throw new IllegalArgumentException("MapDefinition.boundary must not be null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("MapDefinition.rules must not be null");
        }
        if (extensions == null) {
            throw new IllegalArgumentException("MapDefinition.extensions must not be null");
        }
        if (wireExtras == null) {
            throw new IllegalArgumentException("MapDefinition.wireExtras must not be null");
        }

        int declaredTeams = requireDeclaredSize(teams, MAX_TEAMS, "teams");
        int declaredSpawns = requireDeclaredSize(spawns, MAX_SPAWNS, "spawns");
        int declaredRegions = requireDeclaredSize(regions, MAX_REGIONS, "regions");
        int declaredResources = requireDeclaredSize(resources, MAX_RESOURCES, "resources");

        LowerBound lowerBound = new LowerBound();
        lowerBound.chargeKnown(rules, "rules");
        lowerBound.chargeKnown(extensions, "extensions");
        lowerBound.chargeExtras(wireExtras, "wireExtras");

        List<TeamDefinition> localTeams = copyTeams(teams, declaredTeams, lowerBound);
        Set<Ids.TeamId> teamIds = new HashSet<>();
        for (TeamDefinition team : localTeams) {
            if (!teamIds.add(team.id())) {
                throw new IllegalArgumentException(
                        "MapDefinition.teams must be unique by TeamId, but " + team.id().wire() + " occurs twice");
            }
        }

        Set<Ids.ObjectId> objectIds = new HashSet<>();
        List<SpawnDefinition> localSpawns = copySpawns(spawns, declaredSpawns, teamIds, dimensionId,
                objectIds, lowerBound);
        List<RegionDefinition> localRegions = copyRegions(regions, declaredRegions, objectIds,
                lowerBound);
        List<ResourceRevisionRef> localResources = copyResources(resources, declaredResources,
                lowerBound);

        teams = List.copyOf(localTeams);
        spawns = List.copyOf(localSpawns);
        regions = List.copyOf(localRegions);
        resources = List.copyOf(localResources);
    }

    // ------------------------------------------------------- payload lower bound

    /**
     * The single running payload lower bound of one construction attempt.
     *
     * <p>
     * A constructor local object rather than a {@code long} threaded through the helpers: the
     * helpers extend the one bound they are handed, so a bound is never restarted from the root
     * anchors per helper. Restarting it is exactly the defect this type exists to prevent, because
     * two lists could then each stay within the budget on their own while their sum does not.
     * </p>
     *
     * <p>
     * It holds the accumulated byte count and nothing else: no payload, no list, no cached
     * identity, and no state shared between constructions. Every occurrence is charged through
     * {@link #chargeKnown} or {@link #chargeExtras}, before the item that produced it is added to a
     * local list, and a repeated frozen instance is therefore charged once per occurrence.
     * </p>
     */
    private static final class LowerBound {

        /** The accumulated payload lower bound, never negative. */
        private long bytes;

        /**
         * Charges the full canonical UTF-8 size of one known frozen carrier.
         *
         * @param value the frozen carrier, never null
         * @param what  the semantic position used in the failure detail
         * @throws IllegalArgumentException if the extended bound exceeds the exact JSON budget
         */
        private void chargeKnown(ExactJson.FrozenJson value, String what) {
            bytes = addKnown(bytes, value, what);
        }

        /**
         * Charges the member bytes of every frozen carrier of one extras component.
         *
         * @param extras the extras component, never null
         * @param what   the semantic position used in the failure detail
         * @throws IllegalArgumentException if the extended bound exceeds the exact JSON budget
         */
        private void chargeExtras(Object extras, String what) {
            bytes = addExtras(bytes, extras, what);
        }

        /**
         * Rejects a bound that has crossed the exact JSON byte budget.
         *
         * @throws IllegalArgumentException if the accumulated bound exceeds the budget
         */
        private void requireWithin() {
            requireWithinBudget(bytes);
        }
    }

    // ------------------------------------------------------------- size guard

    /**
     * Reads the declared size of a collection exactly once and rejects a negative or over budget
     * value before any element is touched.
     *
     * <p>
     * This is the early guard that makes a lying {@code size()} cheap: no {@code copy}, no
     * {@code toArray()} and no iterator is created here, so a list that claims
     * {@link Integer#MAX_VALUE} elements is refused without any attempt to allocate for it. It is
     * deliberately <em>not</em> a trust decision: the reported size is only a cheap first test, and
     * the real element count is verified during the bounded traversal.
     * </p>
     *
     * <p>
     * The returned value is the one and only reading of the input size. The caller caches it and
     * passes it to the matching copy helper, which uses it for the local capacity, the loop bound and
     * every failure detail. A list whose {@code size()} changes after this guard therefore cannot
     * enlarge the traversal, the capacity or the accepted count: the second reading never happens.
     * </p>
     *
     * @param list  the caller supplied collection
     * @param limit the inclusive budget of that collection
     * @param what  the member name used in the failure detail
     * @return the declared size, already known to satisfy {@code 0 <= size <= limit}
     * @throws IllegalArgumentException if {@code list} is null, or its size is negative or above
     *                                  {@code limit}
     */
    private static int requireDeclaredSize(List<?> list, int limit, String what) {
        if (list == null) {
            throw new IllegalArgumentException("MapDefinition." + what + " must not be null");
        }
        int declared = list.size();
        if (declared < 0) {
            throw new IllegalArgumentException("MapDefinition." + what + " must not report a negative size but reported " + declared);
        }
        if (declared > limit) {
            throw new IllegalArgumentException("MapDefinition." + what + " must contain at most " + limit + " items but reported " + declared);
        }
        return declared;
    }

    // --------------------------------------------------------- bounded copying

    /**
     * Copies the team list into a fresh local list, charging every team payload position.
     *
     * <p>
     * Each team charges its {@code config}, its {@code extensions} and every member of its
     * {@link WireExtras.TeamExtras} before the team itself is added, so a team whose payloads are
     * individually legal but together over budget is refused rather than accepted for being outside
     * the spawn list.
     * </p>
     *
     * @param teams      the caller supplied teams, already size checked
     * @param declared   the cached size of {@code teams}, the only reading of that input
     * @param lowerBound the single running lower bound of this construction
     * @return a fresh mutable local list with the same element order
     */
    private static List<TeamDefinition> copyTeams(List<TeamDefinition> teams, int declared,
                                                  LowerBound lowerBound) {
        List<TeamDefinition> local = new ArrayList<>(declared);
        Iterator<TeamDefinition> iterator = teams.iterator();
        for (int i = 0; i < declared; i++) {
            if (!iterator.hasNext()) {
                throw sizeMismatch("teams", declared, i);
            }
            TeamDefinition team = iterator.next();
            if (team == null) {
                throw new IllegalArgumentException(
                        "MapDefinition.teams must not contain a null element at index " + i);
            }
            lowerBound.chargeKnown(team.config(), "teams[" + i + "].config");
            lowerBound.chargeKnown(team.extensions(), "teams[" + i + "].extensions");
            lowerBound.chargeExtras(team.wireExtras(), "teams[" + i + "].wireExtras");
            local.add(team);
        }
        if (iterator.hasNext()) {
            throw sizeMismatch("teams", declared, declared + 1);
        }
        return local;
    }

    /**
     * Copies the spawn list, resolving the owning team and dimension references and enforcing the
     * shared object identity domain.
     *
     * <p>
     * The team set is fully built by the caller before this method runs, so a spawn whose owning
     * team appears later in the list is accepted and the order of the teams never decides whether a
     * reference is found. Neither a missing team nor a foreign dimension is repaired: no team is
     * created, the spawn is not moved to the first team and it is not dropped.
     * </p>
     *
     * @param spawns      the caller supplied spawns, already size checked
     * @param declared    the cached size of {@code spawns}, the only reading of that input
     * @param teamIds     the complete identity set of this definition's teams
     * @param dimensionId the single dimension this definition is bound to
     * @param objectIds   the shared spawn and region object identity domain
     * @param lowerBound  the single running lower bound of this construction, already charged with
     *                    the root anchors and every team payload position
     * @return a fresh mutable local list with the same element order
     */
    private static List<SpawnDefinition> copySpawns(List<SpawnDefinition> spawns, int declared,
                                                    Set<Ids.TeamId> teamIds, String dimensionId, Set<Ids.ObjectId> objectIds,
                                                    LowerBound lowerBound) {
        List<SpawnDefinition> local = new ArrayList<>(declared);
        Iterator<SpawnDefinition> iterator = spawns.iterator();
        for (int i = 0; i < declared; i++) {
            if (!iterator.hasNext()) {
                throw sizeMismatch("spawns", declared, i);
            }
            SpawnDefinition spawn = iterator.next();
            if (spawn == null) {
                throw new IllegalArgumentException(
                        "MapDefinition.spawns must not contain a null element at index " + i);
            }
            if (!teamIds.contains(spawn.teamId())) {
                throw new IllegalArgumentException("MapDefinition.spawns[" + i + "].teamId " + spawn.teamId().wire() + " does not exist in MapDefinition.teams");
            }
            if (!dimensionId.equals(spawn.dimensionId())) {
                throw new IllegalArgumentException("MapDefinition.spawns[" + i + "].dimensionId must" + " equal MapDefinition.dimensionId '" + dimensionId + "' but was '" + spawn.dimensionId() + "'");
            }
            if (!objectIds.add(spawn.id())) {
                throw new IllegalArgumentException("MapDefinition.spawns and MapDefinition.regions" + " must be unique by ObjectId, but " + spawn.id().wire() + " occurs twice (at spawns[" + i + "])");
            }
            lowerBound.chargeKnown(spawn.extensions(), "spawns[" + i + "].extensions");
            lowerBound.chargeExtras(spawn.wireExtras(), "spawns[" + i + "].wireExtras");
            lowerBound.requireWithin();
            local.add(spawn);
        }
        if (iterator.hasNext()) {
            throw sizeMismatch("spawns", declared, declared + 1);
        }
        return local;
    }

    /**
     * Copies the region list, extending the shared object identity domain and charging the payload
     * lower bound of every region occurrence.
     *
     * @param regions    the caller supplied regions, already size checked
     * @param declared   the cached size of {@code regions}, the only reading of that input
     * @param objectIds  the shared spawn and region object identity domain
     * @param lowerBound the single running lower bound of this construction, already charged with
     *                   the root anchors, every team and every spawn payload position
     * @return a fresh mutable local list with the same element order
     */
    private static List<RegionDefinition> copyRegions(List<RegionDefinition> regions, int declared,
                                                      Set<Ids.ObjectId> objectIds, LowerBound lowerBound) {
        List<RegionDefinition> local = new ArrayList<>(declared);
        Iterator<RegionDefinition> iterator = regions.iterator();
        for (int i = 0; i < declared; i++) {
            if (!iterator.hasNext()) {
                throw sizeMismatch("regions", declared, i);
            }
            RegionDefinition region = iterator.next();
            if (region == null) {
                throw new IllegalArgumentException(
                        "MapDefinition.regions must not contain a null element at index " + i);
            }
            if (!objectIds.add(region.id())) {
                throw new IllegalArgumentException("MapDefinition.spawns and MapDefinition.regions" + " must be unique by ObjectId, but " + region.id().wire() + " occurs twice (at regions[" + i + "])");
            }
            lowerBound.chargeKnown(region.config(), "regions[" + i + "].config");
            lowerBound.chargeKnown(region.extensions(), "regions[" + i + "].extensions");
            lowerBound.chargeExtras(region.wireExtras(), "regions[" + i + "].wireExtras");
            lowerBound.requireWithin();
            local.add(region);
        }
        if (iterator.hasNext()) {
            throw sizeMismatch("regions", declared, declared + 1);
        }
        return local;
    }

    /**
     * Copies the resource reference list, charging the payload lower bound of every occurrence.
     *
     * <p>
     * A resource reference is a value reference, so it takes no part in the object identity
     * domain: repeated occurrences of the same identity and references that share a UUID with a
     * different legal kind or revision are all kept, in their original order, each with its own
     * members.
     * </p>
     *
     * @param resources  the caller supplied resource references, already size checked
     * @param declared   the cached size of {@code resources}, the only reading of that input
     * @param lowerBound the single running lower bound of this construction, already charged with
     *                   the root anchors, every team, every spawn and every region payload position
     * @return a fresh mutable local list with the same element order
     */
    private static List<ResourceRevisionRef> copyResources(List<ResourceRevisionRef> resources,
                                                           int declared, LowerBound lowerBound) {
        List<ResourceRevisionRef> local = new ArrayList<>(declared);
        Iterator<ResourceRevisionRef> iterator = resources.iterator();
        for (int i = 0; i < declared; i++) {
            if (!iterator.hasNext()) {
                throw sizeMismatch("resources", declared, i);
            }
            ResourceRevisionRef reference = iterator.next();
            if (reference == null) {
                throw new IllegalArgumentException(
                        "MapDefinition.resources must not contain a null element at index " + i);
            }
            lowerBound.chargeExtras(reference.wireExtras(), "resources[" + i + "].wireExtras");
            lowerBound.requireWithin();
            local.add(reference);
        }
        if (iterator.hasNext()) {
            throw sizeMismatch("resources", declared, declared + 1);
        }
        return local;
    }

    /**
     * Builds the failure for a traversal whose element count disagrees with the declared size.
     *
     * @param what     the member name used in the failure detail
     * @param declared the size the input reported
     * @param actual   the element count actually observed
     * @return the exception to throw
     */
    private static IllegalArgumentException sizeMismatch(String what, int declared, int actual) {
        return new IllegalArgumentException("MapDefinition." + what + " reported size " + declared + " but its iterator produced " + actual + " elements;" + " the declared size is never trusted as a capacity");
    }

    // --------------------------------------------------------- payload budget

    /**
     * Charges the full canonical UTF-8 size of one known frozen carrier.
     *
     * @param budget the running lower bound
     * @param value  the frozen carrier, never null
     * @param what   the semantic position used in the failure detail
     * @return the extended lower bound
     */
    private static long addKnown(long budget, ExactJson.FrozenJson value, String what) {
        long next = checkedAdd(budget, value.utf8Size(), what);
        requireWithinBudget(next);
        return next;
    }

    /**
     * Charges the member bytes of every frozen carrier of one extras component.
     *
     * <p>
     * Each member contributes {@code utf8Size() - 2}: the two container braces of the extras
     * object are dropped because the formal wire appends the members into the owning object instead
     * of writing a separate {@code {}} object. An empty extras object therefore contributes exactly
     * zero and can never cause a false rejection. Every occurrence is charged, so a shared legal
     * instance is counted once per semantic position and is never deduplicated by identity.
     * </p>
     *
     * @param budget the running lower bound
     * @param extras the extras component, never null
     * @param what   the semantic position used in the failure detail
     * @return the extended lower bound
     */
    private static long addExtras(long budget, Object extras, String what) {
        long running = budget;
        if (extras instanceof WireExtras.MapExtras mapExtras) {
            running = addMember(running, mapExtras.members(), what + ".members");
            running = addMember(running, mapExtras.boundaryMembers(), what + ".boundaryMembers");
            running = addMember(running, mapExtras.boundaryFirstMembers(),
                    what + ".boundaryFirstMembers");
            running = addMember(running, mapExtras.boundarySecondMembers(),
                    what + ".boundarySecondMembers");
        } else if (extras instanceof WireExtras.TeamExtras teamExtras) {
            running = addMember(running, teamExtras.members(), what + ".members");
        } else if (extras instanceof WireExtras.SpawnExtras spawnExtras) {
            running = addMember(running, spawnExtras.members(), what + ".members");
            running = addMember(running, spawnExtras.positionMembers(), what + ".positionMembers");
            running = addMember(running, spawnExtras.orientationMembers(),
                    what + ".orientationMembers");
        } else if (extras instanceof WireExtras.RegionExtras regionExtras) {
            running = addMember(running, regionExtras.members(), what + ".members");
            running = addMember(running, regionExtras.cornersMembers(), what + ".cornersMembers");
            running = addMember(running, regionExtras.cornersFirstMembers(),
                    what + ".cornersFirstMembers");
            running = addMember(running, regionExtras.cornersSecondMembers(),
                    what + ".cornersSecondMembers");
        } else if (extras instanceof WireExtras.ResourceExtras resourceExtras) {
            running = addMember(running, resourceExtras.members(), what + ".members");
        } else {
            throw new IllegalArgumentException("MapDefinition." + what + " must be a WireExtras component but was " + extras.getClass().getName());
        }
        return running;
    }

    /**
     * Charges one extras member by its member bytes, never by its container bytes.
     *
     * @param budget the running lower bound
     * @param member the frozen extras member, never null
     * @param what   the semantic position used in the failure detail
     * @return the extended lower bound
     */
    private static long addMember(long budget, ExactJson.FrozenJson member, String what) {
        long next = checkedAdd(budget, member.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES, what);
        requireWithinBudget(next);
        return next;
    }

    /**
     * Adds two non negative contributions with {@code long} arithmetic, so a sum of many legal
     * occurrences can neither wrap nor be mistaken for a small value.
     *
     * @param budget       the running lower bound
     * @param contribution the contribution of one position
     * @param what         the semantic position used in the failure detail
     * @return the extended lower bound
     */
    private static long checkedAdd(long budget, long contribution, String what) {
        long next = budget + contribution;
        if (next < budget) {
            throw new IllegalArgumentException("MapDefinition payload lower bound overflowed a long" + " at " + what);
        }
        return next;
    }

    /**
     * Rejects a running payload lower bound that has crossed the exact JSON byte budget.
     *
     * @param budget the running lower bound
     * @throws IllegalArgumentException if the bound exceeds {@link ExactJson#MAX_UTF8_BYTES}
     */
    private static void requireWithinBudget(long budget) {
        if (budget > ExactJson.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("MapDefinition payload lower bound must be at most " + ExactJson.MAX_UTF8_BYTES + " UTF-8 bytes but reached " + budget + "; the complete wire budget is enforced by the R06.1 codec");
        }
    }
}
