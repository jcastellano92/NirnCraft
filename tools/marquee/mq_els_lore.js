'use strict';
// Elsweyr marquee place lore (hook, rumour, readable notes). Only for places with no lore yet.
const L = {};

L.dune = {
  hook: 'A caravan crossroads shaped like a wheel: eight lanes of striped tents around a round market, camel pens at the rim, and a city that always seems about to leave.',
  rumour: 'Dune has eight lanes, but on the Wheel they count seven, and never explain why. Ask a camel-driver about the eighth and watch her change the subject to water.',
  notes: [
    { id: 'toll_notice', kind: 'warning', title: 'Notice of the Wheel-Master', author: 'Wheel-Master Khir', at: 'chest',
      pages: ['^BY THE WHEEL, FOR THE WHEEL\n^Each cart pays a copper at the hub, no more, no less. Carts that are late, early or unnamed shall be counted twice.',
        'Camel-drivers are reminded that the Eighth Lane is not a shortcut. It is not a lane. It does not appear on any map, and persons found using it shall be fined, and then thanked, in that order.'] },
    { id: 'fortune_card', kind: 'notes', title: 'Torn Card from a Fortune-Teller\'s Deck', author: 'Nuri', at: 'chest', reveals: 'nevoe_oasis',
      pages: ['#THE WELL\nA card of a woman kneeling at a well, a coin held out in her paw. On the back, scratched in a quick hand: \'Whoever draws this at the Wheel will be thirsty before the third night. Go by Nevoe, not by Temar. The Temar water has a man standing in it.\'',
        'In the margin, in a different ink: \'Do not tell Khir. He does not believe in cards. He believes in knots, and knots have not once told him anything useful.\''] },
  ],
};

L.corinthe = {
  hook: 'A green river city of canal bridges and terraced gardens, where bees outnumber citizens and a garden-roofed cathedral hums with three hundred hives.',
  rumour: 'People forget Elsweyr has rivers. Corinthe does not let them. Every window in the city has a box of flowers, and every flower has a bee that has opinions about the neighbours.',
  notes: [
    { id: 'hive_roll', kind: 'ledger', title: 'Roll of the Garden Cathedral\'s Hives', author: 'Gardener-Priest Mura', at: 'chest',
      pages: ['#HIVES OF THE GARDEN CATHEDRAL\nHive One: Old Mother. Calm, golden, tolerates priests. Hive Seven: Quiet Seven. Sulks in winter, forgives in spring. Hive Eleven: Eleven-Without-Sting. Honey of surprising sweetness, but bees of surprising rudeness.',
        'Report to the Bell on any hive that falls silent. A silent hive is not a dead hive; it is a hive that has decided something. Learn what, before anyone opens the lid.'] },
    { id: 'canal_chalk', kind: 'warning', title: 'Chalk-Writing on a Canal Stair', author: 'Boatman Ajh', at: 'site',
      pages: ['^DO NOT MOOR HERE AFTER MIDNIGHT\n^The third bridge sings. It does not like to be answered. If it asks you a question, say that you are not from Corinthe and that you will pay the toll on the way back.'] },
  ],
};

L.orcrest = {
  hook: 'A mesa-top fort-town in orange rock, its gate carved like a pair of raised claws, where every rooftop is a cistern and every citizen counts water in their sleep.',
  rumour: 'Orcrest is the only city in Elsweyr that closes its gate before dusk. Ask why and a guard will say \'water.\' Ask again and she will say \'thieves.\' Ask a third time and she will ask for your name.',
  notes: [
    { id: 'cistern_roster', kind: 'ledger', title: 'Cistern Roster of Orcrest', author: 'Cistern-Keeper Mala', at: 'chest',
      pages: ['#THE FIVE CISTERNS\nFirst, by the Gate: for guards and caravans. Second, under the Watchtower: for the high houses. Third, beneath the Claw-and-Cistern: the best water, the deepest well, the one nobody speaks of. Fourth, in the lower court. Fifth, which is dry. The Fifth is always dry. Do not ask.',
        'Marked in pencil at the bottom: \'The Third is down a barrel again. Four nights since the last. Raz says it is evaporation. Mala has watched evaporation for thirty years, and it does not take barrels.\''] },
    { id: 'gate_oath', kind: 'inscription', title: 'Oath Carved on the Claw Gate', at: 'site', reveals: 'the_scar',
      pages: ['^WHILE WATER IS IN THE TOWN, THE GATE SHALL STAND.\n^WHILE THE GATE STANDS, THE TOWN SHALL SHARE.\n^IF EITHER FAILS, THE OTHER SHALL REMEMBER.',
        'Below the oath, scratched by a later hand: \'The Gate remembers. It has been remembering the debt of the Fifth Cistern since before my grandmother, and it has not yet forgotten.\''] },
  ],
};

L.hakoshae = {
  hook: 'A small hamlet that grew around a ring of upright moonstones. Tomb-guardians live in low houses in the circle\'s shadow, and pilgrims come for the night the moons stand together.',
  rumour: 'In Hakoshae they say the ring is not stones at all. It is a crowd, patient, that has been standing a thousand years waiting for a late friend.',
  notes: [
    { id: 'keeper_rule', kind: 'inscription', title: 'Rule of the Ring-Keepers', author: 'The first Ring-Keeper', at: 'site',
      pages: ['^SWEEP AT DAWN. WALK SUNWISE. DO NOT STAND IN THE CENTRE.\n^LET NO ONE COUNT THE STONES.\n^IF YOU HEAR YOUR NAME, DO NOT ANSWER UNTIL IT HAS BEEN SAID THREE TIMES.'] },
    { id: 'pilgrim_log', kind: 'journal', title: 'Pilgrim\'s Log of the Moon-Temples', author: 'Ke', at: 'chest', reveals: 'shrine_of_the_crescent',
      pages: ['#My second lap\nRimmen to Hakoshae, four days. The road is longer when you are no longer trying to finish. I find that I enjoy it more now that I have no idea where I am going. At the Shrine of the Crescent I met a bandit who gave me his hat.',
        'Hamza says I should stop. I tell him I have not started. I think this is a joke. I think he thinks it is a lie. Tomorrow I shall go to the Temple of the Hidden Moon and see what the moons are keeping hidden.'] },
  ],
};

L.the_sweet_breezes_inn = {
  hook: 'A three-storey inn on the Senchal waterfront where every room faces the sea and every window has been broken at least once by a wind that insists on coming in.',
  rumour: 'The Sweet Breezes has a tab of glass that no one has ever settled. Zhaleh blames the wind. The wind, to be fair, has never denied it.',
  notes: [
    { id: 'glass_bill', kind: 'ledger', title: 'Zhaleh\'s Glass Bill', author: 'Zhaleh', at: 'chest',
      pages: ['#GLASS, THIS WINTER\nNinth-floor bay window: eleven times. Third-floor bay window: nine. Kitchen window: thrice, and the cook admits to two. Cellar skylight: twice, by a seagull that I will personally murder.',
        'Marginal note: \'The wind pays for what it breaks, in its own way. Three nights a week it leaves a coin on the third-floor sill. I have decided it is penance, and I take it.\''] },
  ],
};

L.the_sugar_bowl = {
  hook: 'A bowl-shaped courtyard tavern in Rimmen, hollowed down into the rock so every table sits in a shade, and run, cooked, swept and sung by one cat.',
  rumour: 'Tamhir keeps seventeen casks in the Sugar Bowl cellar and will tell you only sixteen. The seventeenth is for a guest who has not come yet.',
  notes: [
    { id: 'house_wine', kind: 'list', title: 'What Is In the House Wine', author: 'Tamhir', at: 'chest',
      pages: ['#THE HOUSE WINE\nGrapes, when Tamhir can get them. Plums, when he cannot. Moon-sugar, on feast nights. Honey, because the bees of Corinthe sent a gift last winter and he did not know how to refuse. Cinnamon. A single, very discreet pepper.',
        'Do not ask what else is in it. There is a crossed-out line at the bottom of the list, and a note: \'The cellar tenant has asked that her share be left out of the account. She says she is not a shareholder.\''] },
  ],
};

L.hall_of_the_lunar_champion = {
  hook: 'A long hall of pillars in Rimmen, each cut with the name of a champion of the Midyear challenge. One pillar at the far end has been left blank, and no one has dared to carve it.',
  rumour: 'A hundred and twelve pillars, a hundred and eleven names. The blank one is for the champion that no one has met. People say she is already in the hall, and has been for a thousand years.',
  notes: [
    { id: 'challenge_rules', kind: 'orders', title: 'Rules of the Midyear Challenge', author: 'The Pillar-Warden', at: 'site',
      pages: ['^1. THE CHALLENGE IS OPEN TO ANY WHO WALK UNARMED INTO THE HALL AND WALK OUT ARMED.\n^2. NO CHAMPION MAY BE A PRINCE\'S SON, NEPHEW, OR PRIVATE ASSASSIN.\n^3. THE PILLAR SHALL BE CARVED BY THE LOSER.',
        'At the foot, a late addition in small letters: \'4. Nobody has ever asked what happens if there is no loser. The Pillar-Warden would prefer not to find out.\''] },
  ],
};

L.senchal_merchant_square = {
  hook: 'A broad square of striped awnings behind the Senchal quay where sugar, silks and rumours change hands at the same speed, and almost nobody counts which is which.',
  rumour: 'The square has a rule: whatever you sell in the morning, you may buy back in the afternoon at three times the price. People do this on purpose. It is called loyalty.',
  notes: [
    { id: 'stall_chalk', kind: 'list', title: 'Price Chalk on the Third Stall', author: 'A sugar-wife', at: 'site',
      pages: ['#PRICES TODAY\nMoon sugar, white: 8. Moon sugar, grey: not for sale, ask elsewhere. Candied peel: 3. Honeyed figs: 4, or 2 if you do not ask where they came from. Advice: free. Silence: dear.'] },
  ],
};

L.baandari_caravan_stop = {
  hook: 'A ring of wagons and a ring of fires beside the Rimmen road, where Baandari traders mend axles, trade songs and keep the only route book that every clan agrees on.',
  rumour: 'The Baandari say the road is a living thing, and they have only borrowed it. They return it each evening, with interest, by way of a song.',
  notes: [
    { id: 'route_note', kind: 'notes', title: 'Page of a Route Book', author: 'Ha\'bir', at: 'chest', reveals: 'nekha_camp',
      pages: ['#THE SHORT WAY TO RIMMEN\nWells: Qasari, good; Seri, brackish; Thamokha, salt. Do not camp at Thamokha. A caravan once did, and rose with a very strange hunger.',
        'Price of safe passage past Nekha: three coins, one song, and the polite refusal to ask who is paying whom. Do not carry more than you can sing.'] },
  ],
};

L.the_prancing_netch = {
  hook: 'An inn built under a grown netch-balloon that bobs on a rope above the courtyard, where a balloonist trades rides for stories and has never once been told the same tale twice.',
  rumour: 'The netch is older than the inn, and the inn was built to keep it company. At night, the balloon dreams. The patrons say they can hear it, if they have had enough.',
  notes: [
    { id: 'ride_chalk', kind: 'list', title: 'Chalkboard of the Balloon Rides', author: 'Q\'ri', at: 'site',
      pages: ['#RIDES TODAY\nOne story, one ride. Two stories, one ride and a drink. Three stories, Q\'ri will take you somewhere nobody has been. Rides do not come back the same. Stories do not either.'] },
  ],
};

L.roadside_twin_moons = {
  hook: 'A roadhouse where travellers bet on the phase of the two moons and the keeper keeps losing, and never learns to stop.',
  rumour: 'Ma\'tir has lost eleven cellars, three horses and one husband to the moons, and says the husband was the least interesting loss.',
  notes: [
    { id: 'wager_slate', kind: 'ledger', title: 'The Slate of Wagers', author: 'Ma\'tir', at: 'chest',
      pages: ['#WAGERS OF THIS MONTH\nMasser full, Secunda waning: lost three casks. Both new: lost a mule. Secunda full, Masser a sliver: lost the roof, but only temporarily. The sky is not honest, and I pay for it.'] },
  ],
};

L.moon_sugar_meadow = {
  hook: 'A meadow of pale flowers that glow faintly at night and ripen into the finest moon-sugar only on the one night when both moons are full.',
  rumour: 'You can smell the meadow from a mile off on the right night. They say it smells like a sweet thing you once forgot, and are glad to remember.',
  notes: [
    { id: 'harvest_rule', kind: 'warning', title: 'The Harvest Rule', author: 'An old cane-keeper', at: 'site',
      pages: ['^PICK ONLY WHAT THE MOONS HAVE KISSED.\n^LEAVE THE REST FOR THE NEXT NIGHT.\n^NEVER PICK TWICE FROM THE SAME STEM, FOR THE STEM WILL SULK, AND A SULKING STEM BEARS NO SUGAR FOR A YEAR.'] },
  ],
};

L.tathara_sugar_farm = {
  hook: 'A burned sugar-farm, its cane reduced to black stubble, and an owner who will not say who set the fire.',
  rumour: 'Tathara will not say, but she does not lock her gate any more. She says she wants whoever did it to come back and see.',
  notes: [
    { id: 'burned_ledger', kind: 'ledger', title: 'Water-Stained Ledger of Tathara', author: 'Tathara', at: 'chest',
      pages: ['#THE LAST HARVEST\nThirty years of cane, each year in a hand a little shakier. The final line, written in charcoal: \'Four hands. I counted the footprints. One of them wore the boots my husband gave away at the funeral.\''] },
  ],
};

L.star_haven_adeptorium = {
  hook: 'A tiny academy under a stone dome in the dunes, where three cubs and one tutor map the stars, and one star-chart has gone missing from the lectern.',
  rumour: 'The adeptorium is so small that the stars outnumber the students. Zhazir says this is the correct ratio.',
  notes: [
    { id: 'lesson_slate', kind: 'notes', title: 'A Cub\'s Slate', author: 'A student of Zhazir', at: 'chest',
      pages: ['#TONIGHT\nMasser, big and fat. Secunda, thin and cross. Somewhere low on the south there is a very dim thing that Zhazir says is nothing, in a voice that says it is not nothing. I have drawn it, in pencil, and rubbed it out, and drawn it again.'] },
  ],
};

L.hill_of_shattered_swords = {
  hook: 'A hill planted, row on row, with thousands of broken sword-blades, left where armies fell. When the dry wind crosses it, the blades hum a single long note.',
  rumour: 'You cannot take a blade from the hill. Not because anyone will stop you, but because the blades have a way of finding your hands again at dawn, wherever you left them.',
  notes: [
    { id: 'hill_inscription', kind: 'inscription', title: 'Inscription at the Foot of the Hill', at: 'site',
      pages: ['^HERE ARE THE SWORDS OF THREE ARMIES.\n^NONE OF THEM WON.\n^LET THE WIND SING FOR THE ONES WHO COULD NOT.',
        'A drover\'s scrawl beneath: \'I took a blade once, a small one, for luck. It hummed all the way home and would not stop. I carried it back at night and planted it in the same row. It sang once, short, like a cat settling.\''] },
  ],
};

L.shrine_of_the_cats_divine = {
  hook: 'A stepped shrine whose terraces are lined with giant stone cats, each a different pose, each watching the hill of broken swords with the patience of the very old.',
  rumour: 'Pilgrims leave milk at the feet of the cats and in the morning the bowls are dry. The priests say they evaporate. The cats say nothing, which is what cats say best.',
  notes: [
    { id: 'terrace_prayer', kind: 'prayer', title: 'Prayer of the Third Terrace', at: 'site',
      pages: ['~O watcher with the patient eyes,\n~who sees the swords and does not rise,\n~who knows the moons by their small names:\n~keep the hill, and keep the flame.'] },
  ],
};

L.ne_quin_al = {
  hook: 'The ruined plateau city of the early Khajiit kings, its terraced streets half buried in drifted sand, its palace gate still standing alone against the sky.',
  rumour: 'Scholars come to Ne Quin-al to find the first writing of the Khajiit. The Khajiit who live nearby say the first writing is on their grandmothers\' hands, and has no need of stone.',
  notes: [
    { id: 'scholar_dig', kind: 'journal', title: 'Dig Journal of a Rimmen Scholar', author: 'Scholar Pa\'ris', at: 'chest',
      pages: ['#Day nine\nThe sand is a fine beast, patient and clean. We have uncovered a flight of steps and a doorway that leads to a wall. The wall has a door painted on it. The door has a tiny door painted on it. I am beginning to suspect the first Kings had a sense of humour.',
        'Day twelve. We broke the wall. Behind it, a hall of cats\' paw-prints, thousands of them, pressed into clay, each different. The oldest census in Tamriel. We have not told the Mane.'] },
  ],
};

L.anequina_aqueduct = {
  hook: 'A long arched aqueduct carrying cold mountain water across a gorge, so old that the water on its top is a different colour from the water at its feet.',
  rumour: 'The aqueduct has one more arch than its builders ever planned. The extra arch is at the middle, and the sand under it is always wet.',
  notes: [
    { id: 'mason_mark', kind: 'inscription', title: 'Mason\'s Mark on the Middle Arch', at: 'site',
      pages: ['^BUILT BY A HUNDRED PAWS.\n^FINISHED BY ONE, WHO CARVED THIS MARK AND DID NOT STAY.',
        'Lower down, in a modern chisel: \'The one who finished it was not a mason. The mason fell, and she took his tools. Her name is on no stone. Her arch is the one that has never leaked.\''] },
  ],
};

L.rimmen_masterworks = {
  hook: 'A long-roofed hall of workshops below the Rimmen cliff where the seven guilds of artisans hammer, weave, glaze and argue under the same rafters.',
  rumour: 'No two guilds agree on the colour of the Masterworks roof, and so it has been painted seven times in seven layers, each guild\'s colour on its own stripe.',
  notes: [
    { id: 'guild_rota', kind: 'orders', title: 'Rota of the Seven Guilds', author: 'The Masterworks Warden', at: 'chest',
      pages: ['#THE HALL, BY THE HOUR\nDawn: Smiths. Mid-morning: Weavers. Noon: Glaziers, who are not to be spoken to. Afternoon: Potters. Dusk: Carvers. Night: Jewellers and Lamp-makers, sharing the great table by long-standing and bitter agreement.'] },
  ],
};

L.rimmen_artisans_district = {
  hook: 'The craft quarter of Rimmen, stepped down the cliff in terraces of kiln-smoke and chisel-song, where a hundred artisans work under the shadow of the palace.',
  rumour: 'In the Artisans\' District no one is ever done. Ask a potter if a bowl is finished and she will turn it, and tell you it needs one more glaze.',
  notes: [
    { id: 'commission_board', kind: 'list', title: 'Commission Board', author: 'The Guild of Masons', at: 'site',
      pages: ['#OPEN COMMISSIONS\nFor the Prince: a seven-strand cloth. (Do not ask Kahlil.) For the Temple: nine lamps, each with a different flame. For the Hall: a pillar, blank, to be left blank.\nFor anyone: a spoon for a cat who outlived everyone, ten years in the making.'] },
  ],
};

module.exports = L;
