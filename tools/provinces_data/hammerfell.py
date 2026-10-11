"""Named places of Hammerfell. Facts (names, kinds, relative positions) from the lore; descriptions are original."""

PLACES = [
    # ------------------------------------------------------------------------------------------------------------
    # Alik'r Desert: the Iliac coast around Sentinel, the open dunes and the Abecean shore to the south-west
    # ------------------------------------------------------------------------------------------------------------
    # cities and towns
    ("Gilane", "city", -9900, 6050, "alikr_desert", "An old Redguard port on the Abecean coast, its white walls facing the sea lanes to Stros M'Kai."),
    ("Hegathe", "city", -8150, 6950, "alikr_desert", "A walled southern city of domes and cloisters, long a seat of learning and sterner temple law."),
    ("Kozanset", "town", -7150, 4700, "alikr_desert", "A trading town east of Sentinel where caravans from the dunes meet the ships of the Iliac Bay."),
    ("Bergama", "town", -7750, 6250, "alikr_desert", "A sun-baked desert town on the caravan roads, ringed by old walls and tented markets."),
    ("Satakalaam", "town", -6850, 6250, "alikr_desert", "An eastern desert town built around deep wells, a waystation before the Dragontail foothills."),
    ("Morwha's Bounty", "village", -9100, 5450, "alikr_desert", "A farming village west of Sentinel, named for the goddess of fertility whose fields it tends."),
    ("Antiphyllos", "town", -10250, 5250, "alikr_desert", "The chief town of the western headland, a quiet place of fishing boats and date groves."),
    ("Myrkwasa", "town", -10700, 5000, "alikr_desert", "A windswept town near Hammerfell's westernmost cape, facing the open Abecean."),
    ("Cybiades", "town", -9550, 5650, "alikr_desert", "A market town on the road between Sentinel and the western capes."),
    ("Abibon-Gora", "town", -9150, 6450, "alikr_desert", "A dusty town of the south-western desert whose petty lords have long quarrelled with Sentinel."),
    ("Totambu", "town", -8350, 6300, "alikr_desert", "A desert town named for the ancient Na-Totambu, built over the bones of an older Yokudan city."),
    ("Kairou", "town", -7000, 5350, "alikr_desert", "An inland town between Sentinel and Skaven, home to camel breeders and caravan masters."),
    ("Ephesus", "town", -6650, 4500, "alikr_desert", "A small coastal town where the Alik'r meets the green hills of Bangkorai."),
    # districts
    ("Castle Sentinel", "district", -8180, 4860, "alikr_desert", "The royal palace of Sentinel, seat of the city's kings and their sprawling courts."),
    ("Sentinel Docks", "district", -8260, 4780, "alikr_desert", "Sentinel's busy waterfront, crowded with dhows, warehouses and spice merchants."),
    # estates, stables
    ("Aswala Stables", "stables", -8650, 5950, "alikr_desert", "A desert stable-yard where riders trade horses and camels for the long road south."),
    ("Hunding's Palatial Hall", "estate", -7850, 5250, "alikr_desert", "A grand Yokudan-style hall of courtyards and fountains, raised in honour of Frandar Hunding."),
    # ruins and tombs
    ("Lost City of the Na-Totambu", "yokudan_tomb", -9650, 5950, "alikr_desert", "The buried city of the first Yokudan kings in Tamriel, its halls now haunted by restless dead."),
    ("Tu'whacca's Throne", "yokudan_tomb", -9050, 6050, "alikr_desert", "A great desert temple of the god of souls, where the honoured dead were once sent to the Far Shores."),
    ("Motalion Necropolis", "yokudan_tomb", -6300, 6450, "alikr_desert", "A sprawling city of the dead in the eastern dunes, its tombs guarded by ancestral spirits."),
    ("Santaki", "yokudan_tomb", -8700, 5600, "alikr_desert", "An old Yokudan sepulchre half swallowed by sand, where looters wake things best left asleep."),
    ("Salas En", "ruin", -8800, 6650, "alikr_desert", "The broken pillars of an ancient Redguard citadel, where a dragon was once said to lie in chains."),
    ("Leki's Blade", "keep", -8100, 5900, "alikr_desert", "A fortified sword-school in the desert where the art of the Ansei is still taught."),
    ("HoonDing's Watch", "tower", -9530, 5250, "alikr_desert", "A lonely watchtower on the western cliffs, named for the Redguard god of making way."),
    ("Stonekeep", "keep", -7600, 6700, "alikr_desert", "A forbidding stone fortress deep in the desert, its vaults sunk far below the sand."),
    # landmarks
    ("Sep's Spine", "rock", -8450, 6650, "alikr_desert", "A jagged ridge of rock rising from the dunes like the backbone of a buried serpent."),
    ("Goat's Head Oasis", "source", -7650, 5550, "alikr_desert", "A palm-ringed spring in the open desert, a vital stop for every caravan crossing the Alik'r."),
    ("Shrikes' Aerie", "eyrie", -6150, 6150, "alikr_desert", "A cliff-top roost on the desert's eastern rim, once a stronghold of desert raiders."),
    ("Hollow Waste", "clearing", -6600, 6950, "alikr_desert", "A barren basin of cracked earth and bleached bones in the south-eastern desert."),
    ("Ash'abah Pass", "pass", -6150, 4850, "alikr_desert", "The high road from the Alik'r into Bangkorai, watched over by the Ash'abah who tend the dead."),
    # mines and Dwemer ruins
    ("Divad's Chagrin Mine", "mine", -7300, 5650, "alikr_desert", "A played-out mine named for Divad Hunding's sorrow, now a refuge for desert bandits."),
    ("Coldrock Diggings", "mine", -7650, 4950, "alikr_desert", "Rocky diggings in the hills east of Sentinel, cool and dark against the desert heat."),
    ("Sandblown Mine", "mine", -9800, 5450, "alikr_desert", "A wind-scoured mine in the western hills whose shafts fill with drifting sand."),
    ("Kulati Mines", "mine", -6750, 4950, "alikr_desert", "Mines in the eastern hills that once fed Sentinel's forges with iron."),
    ("Volenfell", "dwemer", -7250, 6050, "alikr_desert", "A vast Dwemer city buried under the desert, its gears still turning in the dark."),
    ("Aldunz", "dwemer", -6400, 5300, "alikr_desert", "A Dwemer ruin in the eastern Alik'r, its bronze halls cracked open by the shifting sands."),
    ("Rkulftzel", "dwemer", -7250, 5050, "alikr_desert", "A Dwemer ruin in the hills east of Sentinel where treasure-hunters often vanish."),
    ("Yldzuun", "dwemer", -10000, 5100, "alikr_desert", "A Dwemer ruin on the western headland, its pipes and pistons buried in dune and scrub."),
    ("Ragnthar", "dwemer", -8900, 6300, "alikr_desert", "A Dwemer ruin sunk in the southern desert, home to a forgotten forge."),

    # ------------------------------------------------------------------------------------------------------------
    # Bangkorai: the wooded hills and passes between the Alik'r, Craglorn and High Rock
    # ------------------------------------------------------------------------------------------------------------
    ("Hallin's Stand", "town", -5650, 4950, "bangkorai", "A Redguard town in southern Bangkorai, guarding the road where the hills give way to desert."),
    ("Lainlyn", "town", -5850, 4050, "bangkorai", "A green coastal town on the Iliac Bay, the old seat of a small Hammerfell kingdom."),
    ("Mournoth", "town", -5050, 4700, "bangkorai", "A hill town of eastern Bangkorai, capital of a once-proud minor kingdom."),
    ("Ayasofya", "town", -5350, 4200, "bangkorai", "A hill town of terraced orchards and domed shrines in the heart of Bangkorai."),
    ("Bangkorai Garrison", "keep", -4850, 4250, "bangkorai", "A massive fortress commanding the narrow way between High Rock and Hammerfell."),
    ("Viridian Watch", "keep", -6050, 4350, "bangkorai", "A ruined hill fort above the coast, its walls overgrown and its cellars full of the dead."),
    ("Bangkorai Pass", "pass", -5500, 4550, "bangkorai", "The winding pass through Bangkorai's hills, contested by every army that marches through."),
    ("Sunken Road", "pass", -5750, 4500, "bangkorai", "An ancient road sunk between steep banks, worn deep by countless caravans."),
    ("Wethers' Cleft", "pass", -4800, 4950, "bangkorai", "A narrow cleft in the eastern hills where herders drive their flocks to summer pasture."),
    ("Troll's Toothpick", "rock", -5300, 3900, "bangkorai", "A tall needle of stone in northern Bangkorai, a landmark for every traveller in the hills."),
    ("Onsi's Breath", "monument", -5900, 4750, "bangkorai", "An old Yokudan shrine to the warrior-god Onsi, where pilgrims pause to bless their blades."),
    ("Murcien's Hamlet", "ruin", -5000, 3900, "bangkorai", "An abandoned hamlet whose folk vanished overnight, leaving only empty houses and whispers."),
    ("Hall of Heroes", "yokudan_tomb", -5350, 5000, "bangkorai", "A Redguard tomb where the greatest champions of Bangkorai were laid to rest with their swords."),
    ("Fallen Grotto", "cave", -5650, 4300, "bangkorai", "A collapsed grotto in the central hills, used as a hideout by outlaws and worse."),
    ("Old Sord's Cave", "cave", -4750, 4500, "bangkorai", "A hillside cave once home to an old hermit, now claimed by beasts and brigands."),
    ("Torog's Spite", "cave", -5250, 4450, "bangkorai", "A cave of goblins and stolen goods deep in the Bangkorai hills."),
    ("Kerbol's Hollow", "cave", -4700, 3750, "bangkorai", "A hollow in the northern hills where cultists gather in secret."),
    ("Blackheart Haven", "smuggler_cave", -5600, 3830, "bangkorai", "A pirate harbour tucked into the Bangkorai coast, home to the Blackheart corsairs."),
    ("Jackdaw Cove", "smuggler_cave", -6250, 4200, "bangkorai", "A rocky cove on the Iliac Bay where smugglers land their cargo by night."),
    ("Razak's Wheel", "dwemer", -5950, 4550, "bangkorai", "A sprawling Dwemer ruin around a great bronze wheel, overrun by constructs and scavengers."),
    ("Klathzgar", "dwemer", -5100, 4050, "bangkorai", "A Dwemer ruin in the Bangkorai hills whose machines have begun to stir again."),
    ("Rkindaleft", "dwemer", -5500, 4800, "bangkorai", "A Dwemer ruin carved into the southern hills, rich in old bronze and older traps."),
    ("Nilata Ruins", "dwemer", -5150, 4900, "bangkorai", "A crumbling Dwemer outpost among the hills, its towers half fallen."),

    # ------------------------------------------------------------------------------------------------------------
    # Craglorn: the high plateau of fallen Nedic cities, between Bangkorai and the Colovian highlands
    # ------------------------------------------------------------------------------------------------------------
    ("Dragonstar", "city", -4100, 2550, "craglorn", "An old city long divided between rival crowns, now home to a famed fighting arena."),
    ("Belkarth", "town", -3880, 3950, "craglorn", "A rebuilt trading town on Craglorn's eastern edge, a haven for adventurers and caravans."),
    ("Haddock's Market", "hamlet", -3950, 4780, "craglorn", "A ramshackle market where traders from Hammerfell and Cyrodiil haggle over desert goods."),
    ("Dragonstar Arena", "keep", -3720, 2450, "craglorn", "A great walled arena where champions fight for glory before roaring crowds."),
    ("Hel Ra Citadel", "keep", -4500, 2450, "craglorn", "An ancient Nedic fortress of the Warrior, where celestial power still lingers."),
    ("Exarch's Stronghold", "keep", -4380, 3250, "craglorn", "A rocky stronghold held by the cultists of the Celestial Serpent."),
    ("Aetherian Archive", "ruin", -3760, 3420, "craglorn", "A Nedic archive of the Mage, built to gather starlight and the knowledge it carries."),
    ("Sanctum Ophidia", "yokudan_tomb", -3870, 4550, "craglorn", "A buried temple of the Serpent, ringed by venom and old Nedic wards."),
    ("Skyreach Pinnacle", "peak", -3800, 3650, "craglorn", "The highest crag of Craglorn, crowned by the ruins of ancient Skyreach."),
    ("Skyreach Hold", "ruin", -4080, 3440, "craglorn", "The broken citadel of Skyreach, once the proud heart of Nedic Craglorn."),
    ("Skyreach Temple", "ruin", -4380, 3480, "craglorn", "A Nedic temple under open sky, its altars dedicated to the constellations."),
    ("Skyreach Catacombs", "yokudan_tomb", -4450, 3700, "craglorn", "Catacombs beneath Skyreach where the Nedic dead rest uneasily."),
    ("Shada's Tear", "ruin", -3950, 3200, "craglorn", "A Nedic ruin in a sunken bowl, said to mark where a weeping goddess's tear fell."),
    ("Seeker's Archive", "ruin", -4050, 3020, "craglorn", "A ruined repository of ancient lore, picked over by scholars and thieves alike."),
    ("Spellscar", "clearing", -4150, 4370, "craglorn", "A blasted stretch of land where wild magic tore open the earth."),
    ("Valley of Scars", "clearing", -4450, 4900, "craglorn", "A scarred valley on Craglorn's southern edge, littered with old battle debris."),
    ("Cradlecrush", "rock", -4300, 2700, "craglorn", "A field of shattered boulders in the north, home to ogres and their crude camps."),
    ("Hircine's Haunt", "den", -4500, 2780, "craglorn", "A wild cavern where the Huntsman's beasts gather under the moon."),
    ("Fearfang Cavern", "den", -3950, 2850, "craglorn", "A cavern of savage beasts in the northern crags."),
    ("Zalgaz's Den", "den", -4180, 4900, "craglorn", "A lair of ogres and their brutish chieftain in southern Craglorn."),
    ("Loth'Na Caverns", "cave", -4450, 4650, "craglorn", "A deep cave system in the southern hills, crawling with ancient horrors."),
    ("Sandy Grotto", "cave", -4450, 4200, "craglorn", "A sandy grotto below the cliffs, sheltering travellers from the plateau winds."),
    ("Earthtear Cavern", "cave", -3850, 4250, "craglorn", "A cavern split by a deep fissure, glittering with crystal veins."),
    ("Ilthag's Undertower", "tower", -4300, 2930, "craglorn", "A strange tower thrust down into the rock, the lair of a long-dead sorcerer."),
    ("Howling Sepulchers", "yokudan_tomb", -4450, 4430, "craglorn", "Wind-wailing tombs of the Nedic dead in southern Craglorn."),
    ("Molavar", "yokudan_tomb", -4200, 4650, "craglorn", "An ancient ruin overrun by cultists seeking the power of the stars."),
    ("Rkhardahrk", "dwemer", -4400, 4050, "craglorn", "A Dwemer ruin west of Elinhir whose guardians still patrol their bronze halls."),
    ("Rkundzelft", "dwemer", -3720, 2750, "craglorn", "A Dwemer ruin in the northern crags of Craglorn, haunted by its forgotten engines."),
    ("Mtharnaz", "dwemer", -3780, 3050, "craglorn", "A Dwemer ruin among the high crags, its halls filled with the hum of old machines."),

    # ------------------------------------------------------------------------------------------------------------
    # Dragontail Mountains: the high ranges east of the Alik'r
    # ------------------------------------------------------------------------------------------------------------
    ("Pothago", "town", -4400, 5400, "dragontail", "A mountain town in the northern Dragontail, perched above a deep river gorge."),
    ("Tigonus", "town", -5300, 6150, "dragontail", "A hill town on the western flank of the Dragontail, guarding a road down to the desert."),
    ("Dak'fron", "town", -5400, 7400, "dragontail", "A town of the southern Dragontail foothills, on the road between Rihad and Taneth."),
    ("Shedungent", "keep", -5000, 5600, "dragontail", "A grim fortress hidden in the Dragontail peaks, rumoured to guard a terrible secret."),

    # ------------------------------------------------------------------------------------------------------------
    # Hew's Bane: the southern peninsula of Taneth and Abah's Landing
    # ------------------------------------------------------------------------------------------------------------
    ("Abah's Landing", "city", -5350, 8450, "hews_bane", "A rough-and-tumble port of merchants and smugglers, where the Thieves Guild keeps its home."),
    ("Thieves Den", "district", -5300, 8400, "hews_bane", "The Thieves Guild's hidden hall beneath Abah's Landing, entered through a dry cistern."),
    ("Thrall Cove", "hamlet", -7650, 7650, "hews_bane", "A pirate cove on the western shore where slavers once sold their captives.", {"flags": "C"}),
    ("Hubalajad Palace", "estate", -5650, 8150, "hews_bane", "A merchant prince's palace in the hills above Abah's Landing."),
    ("Bahraha's Gloom", "cave", -6100, 7550, "hews_bane", "A dark cavern of ancient ruins that runs deep beneath the hills of Hew's Bane."),
    ("Sharktooth Grotto", "smuggler_cave", -7400, 8000, "hews_bane", "A sea cave of jagged rocks, used by smugglers and pirates."),
    ("No Shira Citadel", "ruin", -6150, 8150, "hews_bane", "The ruins of a hilltop citadel overlooking Abah's Landing and the sea."),
    ("Zeht's Displeasure", "ruin", -5700, 7600, "hews_bane", "A blighted ruin, its fields dead since the farm god turned his face away."),

    # ------------------------------------------------------------------------------------------------------------
    # Stros M'Kai: the island off the west coast
    # ------------------------------------------------------------------------------------------------------------
    ("Port Hunding", "district", -10150, 7260, "stros_mkai", "The harbour of Stros M'Kai, a bustling port named for Frandar Hunding."),
    ("Draggin' Tale Inn", "district", -10230, 7160, "stros_mkai", "A crowded harbour tavern in Stros M'Kai, thick with sailors, rumours and pipe smoke."),
    ("Saintsport", "fishing_village", -10000, 7550, "stros_mkai", "A fishing village on Stros M'Kai's southern shore."),
    ("Bthzark", "dwemer", -10450, 7000, "stros_mkai", "A Dwemer ruin on the island's northern hills, guarded by brass constructs."),
    ("N'Gasta's Tower", "mage_tower", -10300, 7480, "stros_mkai", "The tower of the necromancer N'Gasta, standing on a lonely point."),
    ("Stros M'Kai Lighthouse", "lighthouse", -9950, 7050, "stros_mkai", "A lighthouse guiding ships through the dangerous waters around Stros M'Kai."),
]
