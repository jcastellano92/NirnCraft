package com.skycraft.travel;

import com.skycraft.creatures.travel.StriderPorts;
import com.skycraft.roads.Carriages;
import com.skycraft.sailing.FerryRoutes;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.world.WorldSheets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link TravelNetworks}, {@link Carriages}, {@link StriderPorts}, and {@link FerryRoutes}
 * covering all 15 provincial travel networks, the 2,800-block carriage stage cap, cross-sea carriage prevention,
 * distance-scaled pricing, and regional multi-modal transport (Phase T7).
 */
class TravelPlanTest {

    @Test
    void allFifteenProvincialTravelNetworksLoadAndValidate() {
        assertEquals(15, TravelNetworks.PROVINCE_IDS.size(), "Must define all 15 Tamriel + outer-realm travel networks");

        for (String provId : TravelNetworks.PROVINCE_IDS) {
            Optional<TravelNetworks.ProvinceNetwork> opt = TravelNetworks.province(provId);
            assertTrue(opt.isPresent(), "Province travel network must load: " + provId);
            TravelNetworks.ProvinceNetwork net = opt.get();
            assertEquals(provId, net.province());
            assertFalse(net.why().isEmpty(), provId + " must have province-level WorldSheets why citations");
            assertFalse(net.modes().isEmpty(), provId + " must declare supported travel modes");
            assertFalse(net.nodes().isEmpty(), provId + " must contain travel nodes");
            assertFalse(net.routes().isEmpty(), provId + " must contain travel routes");

            for (String cite : net.why()) {
                assertTrue(WorldSheets.resolves(cite), provId + " province why citation must resolve: " + cite);
            }

            for (TravelNode node : net.nodes()) {
                assertFalse(node.why().isEmpty(), provId + " node " + node.id() + " must have why citations");
                for (String cite : node.why()) {
                    assertTrue(WorldSheets.resolves(cite),
                            provId + " node " + node.id() + " why citation must resolve: " + cite);
                }
                if (node.place() != null && !node.place().isBlank()) {
                    Optional<Place> placeOpt = Places.byId(node.place())
                            .or(() -> "ald_ruhn".equals(node.place()) ? Places.byId("aldruhn") : Optional.empty());
                    assertTrue(placeOpt.isPresent(),
                            provId + " node " + node.id() + " references unknown Place: " + node.place());
                    Place place = placeOpt.get();
                    assertEquals(place.x(), node.x(),
                            provId + " node " + node.id() + " x must match Places." + place.id());
                    assertEquals(place.z(), node.z(),
                            provId + " node " + node.id() + " z must match Places." + place.id());
                }
            }

            for (TravelRoute route : net.routes()) {
                assertTrue(TravelNetworks.node(route.from()).isPresent(),
                        provId + " route from-endpoint must exist: " + route.from());
                assertTrue(TravelNetworks.node(route.to()).isPresent(),
                        provId + " route to-endpoint must exist: " + route.to());
                assertFalse(route.why().isEmpty(),
                        provId + " route " + route.from() + "->" + route.to() + " must have why citations");
                for (String cite : route.why()) {
                    assertTrue(WorldSheets.resolves(cite),
                            provId + " route " + route.from() + "->" + route.to() + " why citation must resolve: " + cite);
                }
            }
        }
    }

    @Test
    void carriageRoutesAndOffersRespectMaxDistanceAndNeverCrossOpenSea() {
        for (TravelRoute route : TravelNetworks.allRoutes()) {
            if (route.mode() != TravelMode.CARRIAGE) continue;
            TravelNode a = TravelNetworks.node(route.from()).orElseThrow();
            TravelNode b = TravelNetworks.node(route.to()).orElseThrow();
            double dist = Math.hypot(b.x() - a.x(), b.z() - a.z());
            assertTrue(dist <= Carriages.MAX_CARRIAGE_DISTANCE,
                    "Carriage route " + route.from() + " <-> " + route.to() + " exceeds MAX_CARRIAGE_DISTANCE: " + dist);
        }

        for (Place place : Places.all()) {
            if (place.type() != PlaceType.CAPITAL && place.type() != PlaceType.CITY
                    && place.type() != PlaceType.TOWN && place.type() != PlaceType.VILLAGE) {
                continue;
            }
            List<Carriages.Destination> dests = Carriages.destinations(place);
            for (Carriages.Destination d : dests) {
                assertTrue(d.distance() <= Carriages.MAX_CARRIAGE_DISTANCE,
                        "Carriage destination from " + place.id() + " to " + d.place().id()
                                + " exceeds MAX_CARRIAGE_DISTANCE: " + d.distance());
                assertTrue(d.fare() >= 15, "Carriage fare must be at least 15g: " + d);
                assertTrue(d.hours() >= 1, "Carriage duration must be at least 1h: " + d);
            }
        }

        // Whiterun must reach all 8 other Skyrim hold capitals within the 2,800-block stage cap
        Place whiterun = Places.byId("whiterun").orElseThrow();
        Set<String> whiterunDestIds = Carriages.destinations(whiterun).stream()
                .map(d -> d.place().id())
                .collect(Collectors.toSet());
        for (String holdCap : List.of("solitude", "windhelm", "riften", "markarth",
                "falkreath", "morthal", "dawnstar", "winterhold")) {
            assertTrue(whiterunDestIds.contains(holdCap),
                    "Whiterun carriage must offer Skyrim hold capital: " + holdCap);
        }

        // Cross-sea / island places must never appear in overland carriage offers
        Place solitude = Places.byId("solitude").orElseThrow();
        Set<String> solitudeIds = Carriages.destinations(solitude).stream()
                .map(d -> d.place().id())
                .collect(Collectors.toSet());
        assertFalse(solitudeIds.contains("raven_rock"), "Solitude carriage must not cross sea to Raven Rock");
        assertFalse(solitudeIds.contains("alinor"), "Solitude carriage must not cross sea to Alinor");
        assertFalse(solitudeIds.contains("stros_mkai"), "Solitude carriage must not cross sea to Stros M'Kai");

        Place anvil = Places.byId("anvil").orElseThrow();
        Set<String> anvilIds = Carriages.destinations(anvil).stream()
                .map(d -> d.place().id())
                .collect(Collectors.toSet());
        assertFalse(anvilIds.contains("alinor"), "Anvil carriage must not cross sea to Alinor");
        assertFalse(anvilIds.contains("firsthold"), "Anvil carriage must not cross sea to Firsthold");
        assertFalse(anvilIds.contains("stros_mkai"), "Anvil carriage must not cross sea to Stros M'Kai");

        Place sentinel = Places.byId("sentinel").orElseThrow();
        Set<String> sentinelIds = Carriages.destinations(sentinel).stream()
                .map(d -> d.place().id())
                .collect(Collectors.toSet());
        assertFalse(sentinelIds.contains("stros_mkai"), "Sentinel carriage must not cross sea to Stros M'Kai");

        Place ravenRock = Places.byId("raven_rock").orElseThrow();
        assertTrue(Carriages.destinations(ravenRock).isEmpty(), "Solstheim must not offer overland carriages");
    }

    @Test
    void distanceScaledFaresAndOfferFormattingAreMonotonic() {
        assertEquals(20, Carriages.fareForDistance(0, false));
        assertEquals(15, Carriages.fareForDistance(0, true));
        assertEquals(20 + Math.round(1000 * 0.045f), Carriages.fareForDistance(1000, false));
        assertEquals(Math.round(Carriages.fareForDistance(1000, false) * 0.75f), Carriages.fareForDistance(1000, true));

        int prevUndiscovered = 0;
        int prevDiscovered = 0;
        int prevStrider = 0;
        int prevCoastalFerry = 0;
        int prevOceanFerry = 0;
        for (int d = 0; d <= Carriages.MAX_CARRIAGE_DISTANCE; d += 100) {
            int u = Carriages.fareForDistance(d, false);
            int disc = Carriages.fareForDistance(d, true);
            int strider = StriderPorts.fareForDistance(d);
            int coastal = FerryRoutes.fareForDistance(d, false);
            int ocean = FerryRoutes.fareForDistance(d, true);

            assertTrue(u >= prevUndiscovered, "Undiscovered carriage fare must be monotonic");
            assertTrue(disc >= prevDiscovered, "Discovered carriage fare must be monotonic");
            assertTrue(disc <= u, "Discovered carriage fare must not exceed undiscovered fare");
            assertTrue(strider >= prevStrider, "Silt Strider fare must be monotonic");
            assertTrue(coastal >= prevCoastalFerry, "Coastal ferry fare must be monotonic");
            assertTrue(ocean >= prevOceanFerry, "Ocean ferry fare must be monotonic");
            assertTrue(ocean >= coastal, "Ocean ferry fare must be >= coastal ferry fare at same distance");

            prevUndiscovered = u;
            prevDiscovered = disc;
            prevStrider = strider;
            prevCoastalFerry = coastal;
            prevOceanFerry = ocean;
        }

        assertEquals(250, FerryRoutes.fareForDistance(10_000, true), "Long ocean ferry fare must cap at 250g");
        assertEquals("600m", Carriages.formatDistance(600));
        assertEquals("1.4 km", Carriages.formatDistance(1400));
        assertEquals(2, Carriages.estimateHours(700));
        assertEquals("2h", Carriages.formatDuration(700));
    }

    @Test
    void regionalTransportModesMatchProvinceLoreAndSelftestPasses() {
        // Morrowind: Silt Striders, Boats, Mages Guild guides, 10-stronghold Propylon ring, Interventions, no Carriages
        TravelNetworks.ProvinceNetwork morrowind = TravelNetworks.province("morrowind").orElseThrow();
        Set<TravelMode> mwModes = morrowind.routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertFalse(mwModes.contains(TravelMode.CARRIAGE), "Morrowind must not use overland carriages");
        assertTrue(mwModes.contains(TravelMode.STRIDER), "Morrowind must have Silt Strider routes");
        assertTrue(mwModes.contains(TravelMode.BOAT), "Morrowind must have coastal boat routes");
        assertTrue(mwModes.contains(TravelMode.GUILD_GUIDE), "Morrowind must have Mages Guild guide routes");
        assertTrue(mwModes.contains(TravelMode.PROPYLON), "Morrowind must have Dunmer stronghold Propylon ring");

        assertFalse(StriderPorts.destinations("seyda_neen").isEmpty(), "Seyda Neen must offer Silt Strider routes");
        assertFalse(StriderPorts.destinations("balmora").isEmpty(), "Balmora must offer Silt Strider routes");
        assertFalse(StriderPorts.destinations("ald_ruhn").isEmpty(), "Ald'ruhn must offer Silt Strider routes");

        for (String stronghold : List.of("indoranyon", "marandus", "rotheran", "andasreth", "falensarano",
                "kogoruhn", "hlormaren", "falasmaryon", "valenvaryon", "berandas")) {
            assertEquals(2, TravelNetworks.offersFrom(stronghold, TravelMode.PROPYLON).size(),
                    "Dunmer stronghold must have 2 adjacent Propylon ring neighbors: " + stronghold);
        }

        // Solstheim: Boats, Silt Striders, Foot trails, Wayshrines, no Carriages
        TravelNetworks.ProvinceNetwork solstheim = TravelNetworks.province("solstheim").orElseThrow();
        Set<TravelMode> solModes = solstheim.routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertFalse(solModes.contains(TravelMode.CARRIAGE), "Solstheim must not have carriages");
        assertTrue(solModes.contains(TravelMode.BOAT), "Solstheim must have boat routes");
        assertTrue(solModes.contains(TravelMode.STRIDER), "Solstheim must have southern ashland Silt Strider routes");
        assertTrue(solModes.contains(TravelMode.FOOT), "Solstheim must have Skaal/Thirsk foot trails");
        assertFalse(StriderPorts.destinations("raven_rock").isEmpty(), "Raven Rock must offer Silt Strider travel");

        // Cyrodiil, Valenwood, Elsweyr, Black Marsh, Summerset, and outer realms
        Set<TravelMode> cyroModes = TravelNetworks.province("cyrodiil").orElseThrow()
                .routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertTrue(cyroModes.contains(TravelMode.CARRIAGE));
        assertTrue(cyroModes.contains(TravelMode.GUILD_GUIDE));

        Set<TravelMode> valenwoodModes = TravelNetworks.province("valenwood").orElseThrow()
                .routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertTrue(valenwoodModes.contains(TravelMode.RIVER_BOAT));
        assertTrue(valenwoodModes.contains(TravelMode.LIFT));

        Set<TravelMode> elsweyrModes = TravelNetworks.province("elsweyr").orElseThrow()
                .routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertTrue(elsweyrModes.contains(TravelMode.CARAVAN));

        Set<TravelMode> blackMarshModes = TravelNetworks.province("black_marsh").orElseThrow()
                .routes().stream().map(TravelRoute::mode).collect(Collectors.toSet());
        assertTrue(blackMarshModes.contains(TravelMode.BARGE));
        assertTrue(blackMarshModes.contains(TravelMode.RIVER_BOAT));

        // FerryRoutes helper
        assertFalse(FerryRoutes.destinations("windhelm").isEmpty(), "Windhelm must offer ferry destinations");
        assertFalse(FerryRoutes.destinations("solitude").isEmpty(), "Solitude must offer ferry destinations");
        assertFalse(FerryRoutes.destinations("anvil").isEmpty(), "Anvil must offer ferry destinations");

        // Full TravelNetworks selftest (checks 1..9)
        TravelNetworks.SelftestResult selftest = TravelNetworks.selftest();
        assertEquals(0, selftest.problems(), () -> "TravelNetworks.selftest() reported problems: " + selftest.details());
        assertEquals(selftest.total(), selftest.passed(), "All TravelNetworks selftest checks must pass");
    }
}
