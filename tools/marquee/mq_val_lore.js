'use strict';
// Valenwood marquee place lore. Only for places that had none.
const L = {};

L.silvenar = {
  hook: 'A hidden court beneath hollowed root arches in the Malabal mist, where the Silvenar holds audience in a single hall and fireflies drift through the lantern-light.',
  rumour: 'Silvenar is not on the road. You find it, or it finds you. The old guides say you need only be lost on purpose and very polite about it.',
  notes: [
    { id: 'audience_roll', kind: 'list', title: 'Roll of Audiences Granted', author: 'The Silvenar\'s Scribe', at: 'chest',
      pages: ['#THIS SEASON\'S AUDIENCES\nTo a Pact-Warden of the east, with a grievance about foxes. To a spinner from Falinesti, with a story. To a Khajiit trader from Rawlkha, with a very small gift and a very large favour. Denied: two Imperial envoys, one Thalmor Justiciar, and a man who said he was the Silvenar\'s brother.',
        'A margin in the Scribe\'s hand: \'The man was the Silvenar\'s brother. The Silvenar has been informed. He declined to be audience-ed by himself.\''] },
    { id: 'gate_riddle', kind: 'warning', title: 'Riddle at the Firefly Gate', at: 'site',
      pages: ['^WHAT WALKS IN, BUT NEVER OUT?\n^WHAT SPEAKS WITHOUT A MOUTH?\n^WHAT DOES THE GATE-WARDEN WANT TO HEAR?\n^(Answer all three. Answer wrongly. See what happens. The gate-warden is in a mood.)'] },
  ],
};

L.rawlkha = {
  hook: 'A Khajiiti trade-town of canvas and bamboo strung between the trees of Reaper\'s March, where bazaar tents hang over rope bridges and a forest of lanterns glows at dusk.',
  rumour: 'A Bosmer will not sell you a rope. A Khajiit will sell you a rope, a knot, a lesson in the knot and, on the third day, a bridge. That is the whole difference between the two peoples, and Rawlkha lives in the gap.',
  notes: [
    { id: 'market_pact', kind: 'contract', title: 'The Rawlkha Market Pact', author: 'Reeve of the Rope Market', at: 'chest',
      pages: ['#BE IT KNOWN\nThat no tree shall be felled for a stall. That no stall shall be lit by living flame within a spear\'s length of a bough. That no merchant shall sell the wood of the forest, nor the sap, nor the leaf, nor the ghost of a leaf.',
        'Whereas the Khajiit traders of Rawlkha shall bring what is not of the forest, and the Bosmer of the forest shall buy it, and neither shall remark on the other\'s prices. Signed, in three leaf-prints and one paw.'] },
  ],
};

L.marbruk = {
  hook: 'A glade fort-town of grey-brown stone ringed by a living wall of thorn, with a central fire-pit, watch-posts at every gap and a Watch Hall that never seems to be empty.',
  rumour: 'You cannot cut the Thorn Wall of Marbruk. You can only ask it, and it answers by growing back. Marbruk has been besieged four times, and the wall has won every one without raising a thorn.',
  notes: [
    { id: 'watch_book', kind: 'orders', title: 'Standing Orders of the Marbruk Watch', author: 'Ranger Cael', at: 'chest',
      pages: ['#STANDING ORDERS\n1. Walk the Wall at dusk. 2. Walk it again at dawn. 3. Do not cut. 4. Do not burn. 5. If the wall bleeds sap, say nothing. It is the wall\'s business.',
        'A later hand has added in pencil: \'6. If the wall hums at night, stand very still. 7. If it hums a word, do not say it back.\''] },
  ],
};

L.elden_hollow = {
  hook: 'The great hollow at the root of the Elden Tree, where the market sprawls between arched roots and the sap-lanterns burn amber in the green dusk.',
  rumour: 'Everyone in Elden Hollow looks up when they speak. It is a courtesy to the tree, and has become a habit, and has made the Hollow the best place in Valenwood to pick a pocket.',
  notes: [
    { id: 'root_market_rates', kind: 'list', title: 'Root Market Rates, Third Week of Rain\'s Hand', author: 'The Market Reeve', at: 'chest',
      pages: ['#RATES\nSap-lantern wick, a copper. Bark-tea, two. A night in the hollow-inn, six, with the tree\'s blessing, nine, without. Meat, any, fresh: one silver. Meat, aged, a quarter silver and a note of apology to the cook.'] },
  ],
};

L.the_orrery = {
  hook: 'A hanging orrery of bronze rings and polished wood, slung in the boughs of the Elden Tree, which tracks the moons, the seasons and the slow turning of the great tree itself.',
  rumour: 'The Orrery has one ring too many. The Spinners say it measures the tree\'s heartbeat. The Tree says it measures whatever is listening.',
  notes: [
    { id: 'orrery_log', kind: 'journal', title: 'Log of the Orrery-Keeper', author: 'Aldwe Starhand', at: 'chest',
      pages: ['#Third night, still\nThe outer ring slipped again. It slips every month, always by the same amount, always at the same hour. I have stopped adjusting it. I think the tree moves, a little, each month, and the ring is the only honest witness.',
        'Fifth night: I measured the slip. It is not random. It is a rhythm. The tree is breathing, and the ring is its lung. I have told the Silvenar\'s Voice. He asked me to lower my voice.'] },
  ],
};

L.the_middens = {
  hook: 'A shadowed warren of lean-tos and sunless alleys beneath the lowest roots of Elden Root, where those the Pact forgets sell what the city throws away.',
  rumour: 'Everything that falls from the city ends in the Middens. Nobody lives in the Middens, you understand. They only happen to sleep there, a great many of them, with their eyes open.',
  notes: [
    { id: 'peddler_sign', kind: 'notes', title: 'Peddler\'s Sign, Nailed to a Root', author: 'Sorwe Rat-Tail', at: 'site',
      pages: ['^HONEST LANTERNS\n^Cheap. Quiet. No questions about the bracket.\n^Ask for Sorwe, or for the other fellow, who is not Sorwe.',
        'Scrawled below by a Gate guard: \'Seven lamps gone from the Root Gate this month. Every one of them turns up here, a quarter of the price. Nobody on the Gate can find the cutter. Whoever it is, they never touch the wood.\''] },
  ],
};

L.elden_tree = {
  hook: 'The great tree itself, seen from the whole province, with a glow at its crown at night and a trunk wide enough to hold a city in its bark.',
  rumour: 'People say the Elden Tree has never dropped a leaf. People who live under it say it has, and that you do not want to find it, because it is shaped like a hand.',
  notes: [
    { id: 'sap_charm', kind: 'inscription', title: 'Charm Carved in the Bark at the Lowest Stair', at: 'site',
      pages: ['^TREE OF ALL ROOTS, KEEP US.\n^TREE OF ALL BOUGHS, HOLD US.\n^TREE OF ALL LEAVES, FORGIVE US OUR FIRES, OUR AXES AND OUR HUNGER.',
        'Below the carving, a child has scratched a small, very round face, with the words: \'Thank you for not minding.\''] },
  ],
};

L.falinesti_spring_site = {
  hook: 'The Spring site of the walking city: a ring of pale new boughs and a meadow of white blossom, where the city arrives each year in a wash of petals and leaves quietly in the dusk.',
  rumour: 'On the day the city leaves, the blossoms close. On the day it arrives, they open. In between, the meadow is just a meadow, and the pilgrims sit in it and are quiet.',
  notes: [
    { id: 'pilgrim_stone', kind: 'inscription', title: 'Pilgrim\'s Stone, Spring Site', at: 'site',
      pages: ['^HERE THE CITY ARRIVED, FOR THE ONE HUNDRED AND FORTIETH SPRING.\n^HERE IT WILL ARRIVE AGAIN, AND AGAIN.\n^BE PATIENT. THE ROOTS ARE SLOW, BUT THEY KNOW THE WAY.'] },
  ],
};

L.falinesti_autumn_site = {
  hook: 'The Autumn site of the walking city: a hill of copper and flame-red leaves where the city settles each year, and where the forest drops its acorns with a sound like soft rain.',
  rumour: 'The city likes the autumn site best. Nobody says so. But the roots go in deeper, there, and come out slower, and the Tenders say it is reluctant to leave.',
  notes: [
    { id: 'autumn_tally', kind: 'ledger', title: 'Acorn Tally of the Autumn Site', author: 'Lorekeeper Eri', at: 'chest',
      pages: ['#COUNT OF ACORNS\nFirst week: four thousand and eleven. Second week: six thousand, three hundred. By Last Seed: eleven thousand, give or take a squirrel. One acorn, uncounted, buried by the stream; I marked it with a white stone, and the stone has wandered four paces since.'] },
  ],
};

L.falinesti_winter_site = {
  hook: 'The Winter site of the walking city: a high stony shelf in the Grahtwood hills where the city stands bare and still, hung with frost and icicle-lanterns.',
  rumour: 'Everyone says the city sleeps in winter. The Tenders say it does not sleep. It listens, very hard, for the sound of spring walking.',
  notes: [
    { id: 'frost_prayer', kind: 'prayer', title: 'The Winter Vigil', at: 'site',
      pages: ['~Bare bough, bare bough, hold the sky;\n~old root, old root, do not die;\n~walk, O city, when you will,\n~and wait for us upon the hill.',
        'Pilgrims say the prayer is sung once at sundown and once at dawn, very low, so the frost does not hear it and take offence. A tin cup of warm cider is left on the rock beside the verse, for whoever the prayer is meant for.'] },
  ],
};

L.silvenars_audience_hall = {
  hook: 'A single great hall in the roots of Silvenar, its ceiling a tangle of living arches and its floor of polished bark, where a king who rarely speaks holds audience in lamplight.',
  rumour: 'The hall has seats for forty and a Silvenar who needs none. Most days it is empty. On a few days it is so full that the walls bulge, and nobody can say afterward who was in the room.',
  notes: [
    { id: 'hall_rules', kind: 'orders', title: 'Rules of the Audience Hall', author: 'The Silvenar\'s Voice', at: 'site',
      pages: ['^1. SPEAK ONLY WHEN ADDRESSED.\n^2. DO NOT ADDRESS THE FLOOR.\n^3. IF THE HALL ASKS A QUESTION, ANSWER IT BEFORE THE KING DOES.\n^4. LEAVE BY THE DOOR YOU ENTERED.',
        'Beneath, in the Scribe\'s small hand: \'Rule 5 is not written down. You will know it when you break it.\''] },
  ],
};

L.waterdancer_falls = {
  hook: 'A tall waterfall in Reaper\'s March whose spray throws a standing rainbow, and where, on still days, the spray appears to move in time with a tune you almost remember.',
  rumour: 'They say a dancer once leapt from the top of the falls and did not fall, and that her feet are still on the water. Look, at the right hour, and you may see ripples that are not from the stream.',
  notes: [
    { id: 'fall_verse', kind: 'poem', title: 'The Waterdancer\'s Verse', at: 'site',
      pages: ['~She danced at the lip of the falling water,\n~she danced where the rainbow is born,\n~and the spray was her skirt, and the spray was her daughter,\n~and she danced till the dawn, and the dawn.'] },
  ],
};

L.reapers_henge = {
  hook: 'A stone circle with a bone-white altar on a rise in Reaper\'s March, where the Pact\'s tithe-walks end and the forest lays its yearly gift.',
  rumour: 'Do not count the stones at Reaper\'s Henge. The first Warden who did came up with eleven, and the second with thirteen, and neither would say which they preferred.',
  notes: [
    { id: 'tithe_card', kind: 'orders', title: 'Card of the Tithe-Walk', author: 'Warden Aeriel', at: 'chest',
      pages: ['#THE TITHE-WALK\nBegin at the east stone. Walk sunwise. Carry the gift in your left hand, the lamp in your right. At the altar, say nothing. Leave the gift. Do not look behind you. Do not answer the voice, if it asks for more.',
        'It will ask for more. The Wardens have been very clear: it is not yours to give.'] },
  ],
};

L.thizzrini_arena = {
  hook: 'A ruined arena grown over with vines and jungle roots, where Bosmer and Khajiit champions once fought for sport and the stands still hold their echoes of cheers.',
  rumour: 'The arena has no gate. The vines closed it. Locals say they can hear the crowd on a still night, and the fight, and the final roar.',
  notes: [
    { id: 'champion_bill', kind: 'notes', title: 'Torn Bill of a Match', at: 'site',
      pages: ['#TONIGHT AT THE THIZZRINI\nCOMING TO THE SAND: Lysa Sixfeathers of Haven, against the Ash-Maw of Rawlkha, a bear who will not be named. The crowd shall be fed, the loser shall be forgotten, and the arena shall be left as it was found.',
        'In the margin: \'The Ash-Maw won. The crowd was not fed. The arena was not left as found. The stands are still there.\''] },
  ],
};

L.roadside_green_bough = {
  hook: 'A roadside inn built into a great bough that has been growing a new floor each year, and which no one has been able to stop or explain.',
  rumour: 'The Green Bough has eleven floors now and the roof is a very long way up. At the top the room has no door, and the bough, they say, is still thinking about it.',
  notes: [
    { id: 'floor_register', kind: 'ledger', title: 'Register of the Bough\'s Floors', author: 'Host Tamlin', at: 'chest',
      pages: ['#THE FLOORS\nFirst: the taproom. Second to fifth: rooms. Sixth: storeroom. Seventh: Tamlin\'s own, with the good bed. Eighth to tenth: guest rooms, not yet furnished. Eleventh: the growing floor, newly grown, smelling of sap and surprise.',
        'Below, a note in a different hand: \'It has begun to grow a floor on the outside, too. Do not go out on it.\''] },
  ],
};

L.drabul = {
  hook: 'An Orcish outpost in the Valenwood forest, a stockade of log and stone in a tense truce with the Bosmer neighbours who cannot tell whether the Orcs are guests or guards.',
  rumour: 'Drabul is the only place in Valenwood where a Bosmer will eat at an Orc\'s table. They do not speak of it. They bring their own knives.',
  notes: [
    { id: 'truce_stone', kind: 'inscription', title: 'The Truce-Stone of Drabul', at: 'site',
      pages: ['^ORC AND BOSMER MAY SHARE THIS GROUND.\n^NEITHER MAY CUT A TREE THAT THE OTHER HAS NAMED.\n^NEITHER MAY LEAVE A FEAST BEFORE THE OTHER HAS TASTED.',
        'Scratched below with a knife: \'The Orcs have named every tree. The Bosmer have tasted every feast. Neither side has noticed this is exactly what the stone asked.\''] },
  ],
};

L.jathsogur = {
  hook: 'An Orc stronghold in the Valenwood forest, where a chieftain wants a trial of strength witnessed by a stranger who owes the clan nothing.',
  rumour: 'Jathsogur has two sons and one seat. The older is slow and sure; the younger is quick and clever. Every Orc in the forest has an opinion, and none of them will say it before the tavern closes.',
  notes: [
    { id: 'clan_law', kind: 'orders', title: 'Clan Law of Jathsogur', author: 'Mauhulakh', at: 'chest',
      pages: ['#THE LAW\nA chief rules by right of strength. A chief rules by right of the clan\'s will. A chief who cannot do both is not a chief, but a very large visitor.',
        'The duel shall be witnessed by one who owes the clan nothing. Their word is the law, and their silence is law too.'] },
  ],
};

L.dokrin_monastery = {
  hook: 'A forest monastery where the monks have been singing the same hymn for three days, and are afraid to stop.',
  rumour: 'The monks of Dokrin are a quiet order. That is why the hymn is so loud. It is the loudest thing they have ever done.',
  notes: [
    { id: 'hymn_page', kind: 'book', title: 'The Hymn of the Third Hour', author: 'The Order of Dokrin', at: 'chest',
      pages: ['~Rise, O voice, in the morning wood,\n~sing what is, and sing what could,\n~sing the sap and sing the root,\n~sing the bough and sing the fruit.',
        'The hymn has forty verses and an unwritten forty-first. The monks agree that no one should sing the forty-first. They disagree about why.'] },
  ],
};

L.supplication_house = {
  hook: 'A hermit\'s hut in the forest where a prophecy is carved on the doorframe, one line a year, and every line has come true.',
  rumour: 'Do not read the doorframe aloud. Do not read it silently, either. Read it as if it were a letter from a stranger who knows your name.',
  notes: [
    { id: 'doorframe_copy', kind: 'notes', title: 'Copy of the Doorframe, Forty Lines', author: 'A pilgrim', at: 'chest',
      pages: ['#THE DOORFRAME (EXCERPT)\nYear nine: \'Water will take the low house.\' Year twelve: \'The king in green will go to ground.\' Year twenty-six: \'The brother who left will return in the same boots.\' Year forty: [the line has not yet been carved].',
        'I have tried not to look at the blank. It looks at me anyway.'] },
  ],
};

L.sren_ja = {
  hook: 'A treehouse hamlet where two families have feuded for six years over a single shared bridge-plank, each sweeping it every dawn from opposite ends.',
  rumour: 'The plank of Sren Ja is the cleanest piece of timber in Valenwood and the most hotly disputed. It has never been replaced, because neither side will agree to let the other buy the new one.',
  notes: [
    { id: 'feud_petition', kind: 'letter', title: 'Petition to the Reeve', author: 'Maral', at: 'chest',
      pages: ['#To the Reeve of Sren Ja\nI have swept the plank every dawn for six years and my neighbour has swept it every dawn for the same, and neither of us has ever walked on it. I do not know why we sweep it. I would like to stop. But if I stop, he wins. Please advise.',
        'In the margin, in a stern hand: \'The Reeve advises that you may both stop. The Reeve has also been sweeping this plank for six years, from below.\''] },
  ],
};

module.exports = L;
