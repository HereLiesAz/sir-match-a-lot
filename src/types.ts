export interface Track {
  id: string;
  title: string;
  artist: string;
  bpm: number;
  key: string;
  camelotKey: string;
  progression: string;
  atmosphere: string;
  genres: string[];
  energyLevel: number;
  mixTips: string;
  isUserAdded?: boolean;
  youtubeId?: string | null;
}

export interface MixMatch {
  trackA: Track;
  trackB: Track;
  overallScore: number;
  tempoScore: number;
  keyScore: number;
  progressionScore: number;
  atmosphereScore: number;
  tempoAdvice: string;
  keyAdvice: string;
  tempoDiffPercent: number;
  isHalfTimeDoubleTime: boolean;
  canMixWithNudge: boolean;
}

// Extract Camelot properties: "8A" -> { number: 8, mode: "A" }
export function parseCamelotKey(camelot: string): { number: number; mode: string } | null {
  const match = camelot.trim().match(/^(\d+)([ABab])$/);
  if (!match) return null;
  return {
    number: parseInt(match[1], 10),
    mode: match[2].toUpperCase(),
  };
}

// Calculate Harmonic Match Score and mixing tip based on standard DJ Camelot Wheel principles
export function calculateKeyCompatibility(camelotA: string, camelotB: string): { score: number; description: string } {
  const cA = parseCamelotKey(camelotA);
  const cB = parseCamelotKey(camelotB);

  if (!cA || !cB) {
    return { score: 60, description: "Unknown key relationship" };
  }

  const numA = cA.number;
  const numB = cB.number;
  const modeA = cA.mode;
  const modeB = cB.mode;

  // 1. Same key
  if (numA === numB && modeA === modeB) {
    return { score: 100, description: "Perfect Key Match (In-key blending)" };
  }

  // 2. Relative Major/Minor (Same number, different mode, e.g. 8A to 8B)
  if (numA === numB && modeA !== modeB) {
    return { score: 95, description: "Relative Major/Minor (Emotional shift, extremely smooth)" };
  }

  // 3. Perfect Fifth Shift (Adjacent number, same mode, e.g. 8A to 9A, or 12A to 1A)
  const diff = Math.abs(numA - numB);
  const isAdjacent = diff === 1 || diff === 11;
  if (isAdjacent && modeA === modeB) {
    return {
      score: 90,
      description: numB > numA || (numA === 12 && numB === 1) 
        ? "Perfect Fifth Up (Boosts energy and brightness)"
        : "Perfect Fifth Down (Lowers tension, deepens groove)"
    };
  }

  // 4. Diagonal Shift (Adjacent number, different mode, e.g. 8A to 9B)
  if (isAdjacent && modeA !== modeB) {
    return { score: 75, description: "Diagonal Shift (Smooth chromatic color blend)" };
  }

  // 5. Whole Step Shift (Energy Boost, +2 steps, e.g. 8A to 10A)
  const isTwoSteps = diff === 2 || diff === 10;
  if (isTwoSteps && modeA === modeB) {
    return { score: 70, description: "Whole Step Jump (+2 Energy Boost transition)" };
  }

  // 6. Non-harmonic match but manageable
  return { score: 40, description: "Incompatible Keys (Keep transition short, filter EQ heavily)" };
}

// Calculate Tempo compatibility including half-time/double-time
export function calculateTempoCompatibility(bpmA: number, bpmB: number): {
  score: number;
  advice: string;
  diffPercent: number;
  isHalfTimeDoubleTime: boolean;
  canMixWithNudge: boolean;
} {
  const ratio = bpmB / bpmA;
  
  // Standard straight match difference
  const diffStandard = Math.abs(bpmB - bpmA) / bpmA;
  
  // Half-time difference (B is half of A, e.g. 140 to 70)
  const diffHalf = Math.abs(bpmB - bpmA * 0.5) / (bpmA * 0.5);
  
  // Double-time difference (B is double of A, e.g. 80 to 160)
  const diffDouble = Math.abs(bpmB - bpmA * 2) / (bpmA * 2);

  // Find the closest relationship
  let minDiff = diffStandard;
  let isHalfTimeDoubleTime = false;
  let relativeBpmTarget = bpmA;

  if (diffHalf < minDiff) {
    minDiff = diffHalf;
    isHalfTimeDoubleTime = true;
    relativeBpmTarget = bpmA * 0.5;
  }
  if (diffDouble < minDiff) {
    minDiff = diffDouble;
    isHalfTimeDoubleTime = true;
    relativeBpmTarget = bpmA * 2;
  }

  const diffPercent = (bpmB - relativeBpmTarget) / relativeBpmTarget;
  const diffPercentAbs = Math.abs(diffPercent);

  // Score falls linearly from 100% to 0% as difference goes from 0% to 15%
  const score = Math.max(0, Math.round(100 * (1 - minDiff / 0.15)));
  
  const canMixWithNudge = diffPercentAbs <= 0.06; // Standard pitch fader allows up to ±6% safely without key warping (or with keylock/master tempo)

  let advice = "";
  if (diffPercentAbs < 0.005) {
    advice = isHalfTimeDoubleTime 
      ? `Perfect half/double-time match (${bpmA} ⇄ ${bpmB} BPM)`
      : `Perfect tempo match (${bpmA} BPM)`;
  } else if (canMixWithNudge) {
    const direction = diffPercent > 0 ? "increase" : "decrease";
    const percentStr = (diffPercentAbs * 100).toFixed(1);
    advice = isHalfTimeDoubleTime
      ? `Pitch-compatible half/double-time! Speed up/slow down Track B by ${percentStr}%`
      : `Pitch-compatible! Match tempos by adjusting pitch fader by ${diffPercent > 0 ? "-" : "+"}${percentStr}%`;
  } else {
    advice = `Tempos are too far apart (${bpmA} vs ${bpmB} BPM). Mix during a beat-free outro/ambient breakdown.`;
  }

  return {
    score,
    advice,
    diffPercent: diffPercent * 100,
    isHalfTimeDoubleTime,
    canMixWithNudge
  };
}

// Helper to calculate Progression overlap similarity
export function calculateProgressionCompatibility(progA: string, progB: string): number {
  if (!progA || !progB) return 60;
  
  const cleanA = progA.toLowerCase().replace(/[^a-z0-9\s-]/g, "").split(/\s*-\s*|\s+/);
  const cleanB = progB.toLowerCase().replace(/[^a-z0-9\s-]/g, "").split(/\s*-\s*|\s+/);

  const setA = new Set(cleanA);
  const setB = new Set(cleanB);

  let intersectionSize = 0;
  setA.forEach(val => {
    if (setB.has(val)) intersectionSize++;
  });

  const unionSize = new Set([...cleanA, ...cleanB]).size;
  if (unionSize === 0) return 60;

  // Overlap ratio mapped to 60-100% scale
  const ratio = intersectionSize / unionSize;
  return Math.round(65 + ratio * 35);
}

// Calculate Atmosphere compatibility based on keyword overlap
export function calculateAtmosphereCompatibility(atmosA: string, atmosB: string): number {
  if (!atmosA || !atmosB) return 60;

  const wordsA = atmosA.toLowerCase().split(/[\s,]+/).map(w => w.trim()).filter(w => w.length > 2);
  const wordsB = atmosB.toLowerCase().split(/[\s,]+/).map(w => w.trim()).filter(w => w.length > 2);

  const commonKeywords = ["dark", "groovy", "energetic", "melodic", "bouncy", "ambient", "hypnotic", "uplifting", "euphoric", "heavy", "chill", "atmospheric", "soulful", "industrial"];
  
  // Filter for matching keywords
  const activeA = wordsA.filter(w => commonKeywords.includes(w));
  const activeB = wordsB.filter(w => commonKeywords.includes(w));

  const setA = new Set(activeA.length > 0 ? activeA : wordsA);
  const setB = new Set(activeB.length > 0 ? activeB : wordsB);

  let intersect = 0;
  setA.forEach(w => {
    if (setB.has(w)) intersect++;
  });

  const union = new Set([...setA, ...setB]).size;
  if (union === 0) return 60;

  const ratio = intersect / union;
  return Math.round(60 + ratio * 40);
}

// Run complete mixing point evaluation between two tracks
export function compareTracks(trackA: Track, trackB: Track): MixMatch {
  const tempoResult = calculateTempoCompatibility(trackA.bpm, trackB.bpm);
  const keyResult = calculateKeyCompatibility(trackA.camelotKey, trackB.camelotKey);
  const progressionScore = calculateProgressionCompatibility(trackA.progression, trackB.progression);
  const atmosphereScore = calculateAtmosphereCompatibility(trackA.atmosphere, trackB.atmosphere);

  // Overall match scoring weights:
  // - Tempo: 30%
  // - Key Compatibility: 30%
  // - Progression Overlap: 20%
  // - Atmosphere Vibe: 20%
  const overallScore = Math.round(
    tempoResult.score * 0.30 +
    keyResult.score * 0.30 +
    progressionScore * 0.20 +
    atmosphereScore * 0.20
  );

  return {
    trackA,
    trackB,
    overallScore,
    tempoScore: tempoResult.score,
    keyScore: keyResult.score,
    progressionScore,
    atmosphereScore,
    tempoAdvice: tempoResult.advice,
    keyAdvice: keyResult.description,
    tempoDiffPercent: tempoResult.diffPercent,
    isHalfTimeDoubleTime: tempoResult.isHalfTimeDoubleTime,
    canMixWithNudge: tempoResult.canMixWithNudge
  };
}

// Mock DJ crates for onboarding
export const MOCK_DJ_CRATES: Record<string, Track[]> = {
  "Tech House & Club Grooves": [
    {
      id: "th1",
      title: "Around the World",
      artist: "Daft Punk",
      bpm: 121,
      key: "A minor",
      camelotKey: "8A",
      progression: "Am - C - Em - G",
      atmosphere: "hypnotic, funk-house, looping bassline, vocal repetition, groovy",
      genres: ["French House", "Electronic"],
      energyLevel: 8,
      mixTips: "Great introductory track. Bring in a deep baseline during the vocal pauses."
    },
    {
      id: "th2",
      title: "One More Time",
      artist: "Daft Punk",
      bpm: 123,
      key: "G major",
      camelotKey: "9B",
      progression: "G - D - Em - C",
      atmosphere: "celebratory, euphoric house, uplifiting filter sweep, iconic brass",
      genres: ["French House", "Dance"],
      energyLevel: 9,
      mixTips: "Seamlessly transition into Daft Punk classics. Boost high frequencies on transition."
    },
    {
      id: "th3",
      title: "Billie Jean",
      artist: "Michael Jackson",
      bpm: 117,
      key: "F# minor",
      camelotKey: "11A",
      progression: "F#m - G#m - A - G#m",
      atmosphere: "groovy, driving drums, tight electronic snap, iconic analog bassline",
      genres: ["Dance-Pop", "Funk"],
      energyLevel: 7,
      mixTips: "Slow down Around the World by -3.3% or speed up Billie Jean by +3.4% to match tempos perfectly."
    },
    {
      id: "th4",
      title: "Levels",
      artist: "Avicii",
      bpm: 126,
      key: "C# minor",
      camelotKey: "12A",
      progression: "C#m - E - B - A",
      atmosphere: "euphoric, high energy leads, nostalgic progressive hooks, clean synthesizer",
      genres: ["Progressive House", "EDM"],
      energyLevel: 9,
      mixTips: "Mix right on the vocal intro. Camelot key 12A matches F# minor (11A) harmonically with a +1 shift."
    }
  ],
  "Peak-Time Techno & Electro": [
    {
      id: "tc1",
      title: "Blue Monday",
      artist: "New Order",
      bpm: 130,
      key: "D minor",
      camelotKey: "7A",
      progression: "Dm - C - F - G",
      atmosphere: "dark wave, retro, mechanical synth, melancholy vocals, rolling kick drum",
      genres: ["Synth-Pop", "Electro"],
      energyLevel: 8,
      mixTips: "Utilize the 16-bar intro beat for beatmatching before bringing in the main melody."
    },
    {
      id: "tc2",
      title: "Sandstorm",
      artist: "Darude",
      bpm: 136,
      key: "F minor",
      camelotKey: "4A",
      progression: "Fm - Ab - Eb - Db",
      atmosphere: "pumping leads, fast tempo, relentless energy, synth arpeggios",
      genres: ["Trance", "Classic Rave"],
      energyLevel: 10,
      mixTips: "Extremely high energy. Slow down Sandstorm or use short cuts to avoid clashing synths."
    },
    {
      id: "tc3",
      title: "Strobe",
      artist: "deadmau5",
      bpm: 128,
      key: "Bb minor",
      camelotKey: "3A",
      progression: "Bbm - Gb - Db - Ab",
      atmosphere: "melodic progressive, emotional building, cinematic textures, rich atmospheric pads",
      genres: ["Progressive House"],
      energyLevel: 7,
      mixTips: "Matches Blue Monday (7A) via relative keys. Start mixing during the deep progressive drop."
    }
  ],
  "Dubstep & Drum n Bass Half-Time": [
    {
      id: "db1",
      title: "Scary Monsters and Nice Sprites",
      artist: "Skrillex",
      bpm: 140,
      key: "D minor",
      camelotKey: "7A",
      progression: "Dm - F - C - G",
      atmosphere: "aggressive, heavy, vocal cuts, grinding growl, peak-energy",
      genres: ["Dubstep", "Electro"],
      energyLevel: 9,
      mixTips: "Perfect for half-time mixing with 70 BPM lofi or 140/174 double time. Blend on the main vocal shout."
    },
    {
      id: "db2",
      title: "Deep Chill Liquid DnB",
      artist: "Sub Focus",
      bpm: 174,
      key: "D minor",
      camelotKey: "7A",
      progression: "Dm - Bb - Gm - C",
      atmosphere: "smooth, rolling sub-bass, rapid breakbeats, atmospheric vocals",
      genres: ["Drum & Bass", "Liquid DnB"],
      energyLevel: 8,
      mixTips: "A perfect harmonic match (both 7A). Can be mixed using 1.25x tempo shifts or transitioning on clean snare breaks."
    },
    {
      id: "db3",
      title: "Sunset Lofi Lounge",
      artist: "ChilledCow",
      bpm: 70,
      key: "D minor",
      camelotKey: "7A",
      progression: "Dm7 - G7 - Cmaj7",
      atmosphere: "mellow lounge, dusty crackles, slow jazzy rhodes, warm background hum",
      genres: ["Lofi Hip-Hop"],
      energyLevel: 3,
      mixTips: "A half-time BPM match to 140 BPM Dubstep! Build up the energy by transition from lofi straight into the heavy dubstep drop."
    }
  ]
};
