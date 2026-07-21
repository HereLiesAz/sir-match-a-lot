import React, { useState, useEffect, useRef } from "react";
import { 
  Play, Pause, RotateCcw, Volume2, VolumeX, Zap, ArrowLeft, ArrowRight, 
  HelpCircle, Youtube, Tv, Link2, Search, Mic, Square, Sparkles, Disc, 
  Scissors, Activity, Radio, Volume1, Shuffle, Sliders, Orbit
} from "lucide-react";
import { Track, compareTracks } from "../types";
import EnergyGraph from "./EnergyGraph";
import RadialController from "./RadialController";

// Declare YouTube IFrame Player global types
declare global {
  interface Window {
    onYouTubeIframeAPIReady?: () => void;
    YT?: any;
  }
}

interface DeckState {
  track: Track | null;
  baseBpm: number;
  pitch: number; // Pitch fader percentage (-8% to +8%)
  bpm: number; // Active adjusted BPM
  phaseOffset: number; // Phase offset in degrees/milliseconds (0-360 deg or ms offset)
  isMuted: boolean;
  autoStretch: boolean; // Auto Stretch (Master Tempo / Key Lock)
  transposeOffset: number; // Digital transposition offset in semitones
}

interface SamplerPad {
  id: number;
  name: string;
  color: string;
  synthType: "kick" | "snare" | "hihat" | "vocal" | "sweep" | "bass" | "chord" | "laser";
  isAssigned: boolean;
  isPlaying: boolean;
  isRecording: boolean;
  recordedBuffer: AudioBuffer | null;
  isLoop?: boolean;
}

interface BeatgridToolProps {
  tracks: Track[];
  initialDeckATrack?: Track | null;
  initialDeckBTrack?: Track | null;
}

// Chromatic lookup tables for exact mathematical key transposition
const CHROMATIC_MINOR = ["Am", "A#m", "Bm", "Cm", "C#m", "Dm", "D#m", "Em", "Fm", "F#m", "Gm", "G#m"];
const CAMELOT_MINOR = ["8A", "3A", "10A", "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A"];

const CHROMATIC_MAJOR = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
const CAMELOT_MAJOR = ["8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B"];

// Key transposition helper
const transposeCamelotKey = (camelotKey: string, semitones: number): { camelot: string; standard: string } => {
  if (semitones === 0) {
    return { camelot: camelotKey, standard: "" };
  }
  const cleanKey = camelotKey.trim().toUpperCase();
  const match = cleanKey.match(/^(\d+)([AB])$/);
  if (!match) return { camelot: camelotKey, standard: "" };

  const num = parseInt(match[1], 10);
  const mode = match[2];

  if (mode === "A") {
    const idx = CAMELOT_MINOR.indexOf(cleanKey);
    if (idx === -1) return { camelot: camelotKey, standard: "" };
    const targetIdx = (idx + semitones + 24) % 12;
    return {
      camelot: CAMELOT_MINOR[targetIdx],
      standard: CHROMATIC_MINOR[targetIdx]
    };
  } else {
    const idx = CAMELOT_MAJOR.indexOf(cleanKey);
    if (idx === -1) return { camelot: camelotKey, standard: "" };
    const targetIdx = (idx + semitones + 24) % 12;
    return {
      camelot: CAMELOT_MAJOR[targetIdx],
      standard: CHROMATIC_MAJOR[targetIdx]
    };
  }
};

// Auto Pitch / Key Sync helper (finds shortest semitone difference to harmonize key B with key A)
const getShortestSemitoneShift = (keyA: string, keyB: string): number => {
  const matchA = keyA.toUpperCase().match(/^(\d+)([AB])$/);
  const matchB = keyB.toUpperCase().match(/^(\d+)([AB])$/);
  if (!matchA || !matchB) return 0;

  const numA = parseInt(matchA[1], 10);
  const modeA = matchA[2];
  const numB = parseInt(matchB[1], 10);
  const modeB = matchB[2];

  const listA = modeA === "A" ? CAMELOT_MINOR : CAMELOT_MAJOR;
  const listB = modeB === "A" ? CAMELOT_MINOR : CAMELOT_MAJOR;

  const idxA = listA.indexOf(keyA.toUpperCase());
  const idxB = listB.indexOf(keyB.toUpperCase());

  if (idxA === -1 || idxB === -1) return 0;

  let diff = (idxA - idxB + 12) % 12;
  if (diff > 6) diff -= 12;
  return diff;
};

export default function BeatgridTool({ tracks, initialDeckATrack, initialDeckBTrack }: BeatgridToolProps) {
  // Setup standard state for both decks with expanded pitch lock and digital transposition options
  const [deckA, setDeckA] = useState<DeckState>({
    track: initialDeckATrack || null,
    baseBpm: initialDeckATrack?.bpm || 120,
    pitch: 0,
    bpm: initialDeckATrack?.bpm || 120,
    phaseOffset: 0,
    isMuted: true,
    autoStretch: true, // Key lock (Master Tempo) on by default
    transposeOffset: 0,
  });

  const [deckB, setDeckB] = useState<DeckState>({
    track: initialDeckBTrack || null,
    baseBpm: initialDeckBTrack?.bpm || 120,
    pitch: 0,
    bpm: initialDeckBTrack?.bpm || 120,
    phaseOffset: 0.15, // slightly off phase by default for training
    isMuted: true,
    autoStretch: true, // Key lock (Master Tempo) on by default
    transposeOffset: 0,
  });

  const [isPlaying, setIsPlaying] = useState(false);
  const [audioVolume, setAudioVolume] = useState(0.4);
  const [isAudioContextInitialized, setIsAudioContextInitialized] = useState(false);
  const [showHelp, setShowHelp] = useState(false);

  // Sampler state (with 8 pads, initial 4 assigned, remaining 4 empty ready for Auto Grab or custom record)
  const [samplerPads, setSamplerPads] = useState<SamplerPad[]>([
    { id: 1, name: "808 Sub Kick", color: "from-rose-500/10 to-rose-500/20 border-rose-500/40 text-rose-400 hover:bg-rose-500/30", synthType: "kick", isAssigned: true, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 2, name: "Retro Snare", color: "from-orange-500/10 to-orange-500/20 border-orange-500/40 text-orange-400 hover:bg-orange-500/30", synthType: "snare", isAssigned: true, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 3, name: "Open Hi-Hat", color: "from-amber-500/10 to-amber-500/20 border-amber-500/40 text-amber-400 hover:bg-amber-500/30", synthType: "hihat", isAssigned: true, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 4, name: "Formant Vox", color: "from-purple-500/10 to-purple-500/20 border-purple-500/40 text-purple-400 hover:bg-purple-500/30", synthType: "vocal", isAssigned: true, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 5, name: "Empty (P5)", color: "from-zinc-800/10 to-zinc-800/20 border-zinc-850 text-zinc-500 hover:bg-zinc-800/40", synthType: "sweep", isAssigned: false, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 6, name: "Empty (P6)", color: "from-zinc-800/10 to-zinc-800/20 border-zinc-850 text-zinc-500 hover:bg-zinc-800/40", synthType: "bass", isAssigned: false, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 7, name: "Empty (P7)", color: "from-zinc-800/10 to-zinc-800/20 border-zinc-850 text-zinc-500 hover:bg-zinc-800/40", synthType: "chord", isAssigned: false, isPlaying: false, isRecording: false, recordedBuffer: null },
    { id: 8, name: "Empty (P8)", color: "from-zinc-800/10 to-zinc-800/20 border-zinc-850 text-zinc-500 hover:bg-zinc-800/40", synthType: "laser", isAssigned: false, isPlaying: false, isRecording: false, recordedBuffer: null },
  ]);

  const [activeTab, setActiveTab] = useState<"decks" | "sampler" | "energy" | "automix" | "radial">("decks");
  const [feedbackMsg, setFeedbackMsg] = useState("");

  // --- REAL-TIME TIME TRACKING & PLAYBACK ---
  const [timeA, setTimeA] = useState<number>(0);
  const [timeB, setTimeB] = useState<number>(0);
  const [durationA, setDurationA] = useState<number>(180);
  const [durationB, setDurationB] = useState<number>(180);

  // --- CUE POINT STATE ---
  const [deckACues, setDeckACues] = useState<(number | null)[]>([null, null, null, null]);
  const [deckBCues, setDeckBCues] = useState<(number | null)[]>([null, null, null, null]);

  // --- ACTIVE LOOP SAMPLER SOURCES REF ---
  const activeLoopSourcesRef = useRef<Record<number, { source: AudioBufferSourceNode; gain: GainNode }>>({});

  // --- AUTOMATIC LOOP MAKER CONFIG ---
  const [selectedLoopTrackId, setSelectedLoopTrackId] = useState<string>("");
  const [selectedLoopPadId, setSelectedLoopPadId] = useState<number>(5); // Default to pad 5
  const [selectedLoopBeats, setSelectedLoopBeats] = useState<number>(4);  // Default to 1 bar (4 beats)

  // --- DEVELOPER API EXPLORER STATE ---
  const [isApiHubExpanded, setIsApiHubExpanded] = useState(false);
  const [apiConsoleLogs, setApiConsoleLogs] = useState<string[]>(["[System API Initialization Completed] Console ready for instructions."]);

  const addApiLog = (msg: string) => {
    setApiConsoleLogs(prev => [`[${new Date().toLocaleTimeString()}] ${msg}`, ...prev.slice(0, 9)]);
  };

  // --- KAOSS / KITARA TOUCH VECTOR SYSTEM ---
  const [selectedKaossPadId, setSelectedKaossPadId] = useState<number>(1);
  const [kaossX, setKaossX] = useState<number>(0.5);
  const [kaossY, setKaossY] = useState<number>(0.5);
  const [isKaossActive, setIsKaossActive] = useState<boolean>(false);
  const [kaossFxType, setKaossFxType] = useState<"none" | "delay" | "stutter">("none");
  const kaossAudioRef = useRef<{
    oscs?: OscillatorNode[];
    filter?: BiquadFilterNode;
    gain?: GainNode;
    noise?: AudioBufferSourceNode;
    delay?: DelayNode;
    feedbackGain?: GainNode;
    stutterInterval?: any;
    waveShaper?: WaveShaperNode;
  } | null>(null);

  // --- AUTOMATCHIC MIX STATE ---
  const [isAutoMixing, setIsAutoMixing] = useState(false);
  const [autoMixPlaylist, setAutoMixPlaylist] = useState<Track[]>([]);
  const [autoMixCurrentIndex, setAutoMixCurrentIndex] = useState(0);
  const [autoMixDuration, setAutoMixDuration] = useState(30); // in seconds
  const [autoMixTimeRemaining, setAutoMixTimeRemaining] = useState(30);
  const [autoMixStage, setAutoMixStage] = useState<"ready" | "sync" | "keysync" | "grab" | "fx" | "fade" | "complete">("ready");
  const [autoMixCrossfader, setAutoMixCrossfader] = useState(-100); // -100 (fully Deck A) to 100 (fully Deck B)
  const [autoMixStatus, setAutoMixStatus] = useState("Ready to launch seamless automated session.");
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const recordingPadIdRef = useRef<number | null>(null);

  // Sync parameters
  const [alignmentScore, setAlignmentScore] = useState(100);

  // --- SHARE SESSION STATE ---
  const [shareLink, setShareLink] = useState<string>("");
  const [showShareBanner, setShowShareBanner] = useState<boolean>(false);

  // Audio Context Ref
  const audioCtxRef = useRef<AudioContext | null>(null);

  // --- MULTI-DEVICE SYNC STATE ---
  const [roomCode, setRoomCode] = useState<string>("");
  const [deviceRole, setDeviceRole] = useState<"all" | "deckA" | "deckB" | "sampler" | "mixer">("all");
  const [deviceName, setDeviceName] = useState<string>(() => {
    const adjectives = ["Epic", "Hyper", "Sonic", "Pro", "Beat", "Wave", "Quantum", "Electro"];
    const nouns = ["Console", "Platter", "Trigger", "Fader", "Deck", "Sync", "Pad", "Mixer"];
    return `${adjectives[Math.floor(Math.random() * adjectives.length)]} ${nouns[Math.floor(Math.random() * nouns.length)]}`;
  });
  const [connectedClients, setConnectedClients] = useState<{ id: string; role: string; name: string }[]>([]);
  const [roomStateSynced, setRoomStateSynced] = useState<boolean>(false);
  const [isWSAconnecting, setIsWSAconnecting] = useState<boolean>(false);

  const wsRef = useRef<WebSocket | null>(null);
  const isIncomingSyncRef = useRef<boolean>(false);

  // WebSockets State Update Sender
  const sendWSState = (partialState: any) => {
    if (isIncomingSyncRef.current) return;
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN && roomCode) {
      wsRef.current.send(JSON.stringify({
        type: "update_state",
        roomCode: roomCode.toUpperCase(),
        state: partialState
      }));
    }
  };

  // WebSockets Event Trigger Sender
  const sendWSEvent = (event: string, payload: any) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN && roomCode) {
      wsRef.current.send(JSON.stringify({
        type: "trigger_event",
        roomCode: roomCode.toUpperCase(),
        event,
        payload
      }));
    }
  };

  // Connect & maintain WebSocket Room
  const connectToSyncRoom = (code: string, roleOverride?: "all" | "deckA" | "deckB" | "sampler" | "mixer") => {
    if (!code) return;
    setIsWSAconnecting(true);

    if (wsRef.current) {
      wsRef.current.close();
    }

    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const host = window.location.host;
    const socketUrl = `${protocol}//${host}`;
    
    console.log("[WS Sync] Connecting to:", socketUrl);
    const ws = new WebSocket(socketUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setIsWSAconnecting(false);
      setRoomStateSynced(true);
      // Join Room
      ws.send(JSON.stringify({
        type: "join",
        roomCode: code.toUpperCase(),
        role: roleOverride || deviceRole,
        name: deviceName
      }));
      setFeedbackMsg(`⚡ Linked to Multi-Device Session [${code.toUpperCase()}]!`);
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        const { type } = data;

        if (type === "init_state") {
          const { roomState } = data;
          isIncomingSyncRef.current = true;
          
          if (roomState.isPlaying !== undefined) setIsPlaying(roomState.isPlaying);
          if (roomState.audioVolume !== undefined) setAudioVolume(roomState.audioVolume);
          if (roomState.crossfader !== undefined) setAutoMixCrossfader(roomState.crossfader);

          if (roomState.deckA) {
            setDeckA(prev => ({ ...prev, ...roomState.deckA }));
            if (roomState.deckA.track) {
              setYoutubeIdA(roomState.deckA.track.youtubeId || null);
            }
          }
          if (roomState.deckB) {
            setDeckB(prev => ({ ...prev, ...roomState.deckB }));
            if (roomState.deckB.track) {
              setYoutubeIdB(roomState.deckB.track.youtubeId || null);
            }
          }
          if (roomState.deckA?.cues) setDeckACues(roomState.deckA.cues);
          if (roomState.deckB?.cues) setDeckBCues(roomState.deckB.cues);
          
          setTimeout(() => {
            isIncomingSyncRef.current = false;
          }, 100);
        }

        else if (type === "state_synced") {
          const { state } = data;
          isIncomingSyncRef.current = true;

          if (state.isPlaying !== undefined) {
            setIsPlaying(state.isPlaying);
            if (state.isPlaying) {
              if (playerARef.current && typeof playerARef.current.playVideo === "function") playerARef.current.playVideo();
              if (playerBRef.current && typeof playerBRef.current.playVideo === "function") playerBRef.current.playVideo();
            } else {
              if (playerARef.current && typeof playerARef.current.pauseVideo === "function") playerARef.current.pauseVideo();
              if (playerBRef.current && typeof playerBRef.current.pauseVideo === "function") playerBRef.current.pauseVideo();
            }
          }
          if (state.audioVolume !== undefined) setAudioVolume(state.audioVolume);
          if (state.crossfader !== undefined) setAutoMixCrossfader(state.crossfader);

          if (state.deckA) {
            setDeckA(prev => ({ ...prev, ...state.deckA }));
            if (state.deckA.track !== undefined) {
              setYoutubeIdA(state.deckA.track ? state.deckA.track.youtubeId : null);
            }
            if (state.deckA.currentTime !== undefined && playerARef.current && typeof playerARef.current.seekTo === "function") {
              playerARef.current.seekTo(state.deckA.currentTime, true);
            }
          }
          if (state.deckB) {
            setDeckB(prev => ({ ...prev, ...state.deckB }));
            if (state.deckB.track !== undefined) {
              setYoutubeIdB(state.deckB.track ? state.deckB.track.youtubeId : null);
            }
            if (state.deckB.currentTime !== undefined && playerBRef.current && typeof playerBRef.current.seekTo === "function") {
              playerBRef.current.seekTo(state.deckB.currentTime, true);
            }
          }
          if (state.deckA?.cues !== undefined) setDeckACues(state.deckA.cues);
          if (state.deckB?.cues !== undefined) setDeckBCues(state.deckB.cues);

          setTimeout(() => {
            isIncomingSyncRef.current = false;
          }, 100);
        }

        else if (type === "event_triggered") {
          const { event: ev, payload } = data;
          
          if (ev === "play_sampler_pad") {
            const pad = samplerPads.find(p => p.id === payload.padId);
            if (pad) playSamplerSound(pad, true);
          }
          else if (ev === "kaoss_move") {
            if (kaossCanvasRef.current) {
              const ctx = kaossCanvasRef.current.getContext("2d");
              if (ctx) {
                const w = kaossCanvasRef.current.width;
                const h = kaossCanvasRef.current.height;
                kaossTrailRef.current.push({
                  x: payload.x * w,
                  y: payload.y * h,
                  r: 32,
                  color: payload.color || "rgba(6, 182, 212, 0.7)"
                });
                const pad = samplerPads.find(p => p.id === selectedKaossPadId) || samplerPads[0];
                updateKaossSound(pad, payload.x, payload.y);
              }
            }
          }
          else if (ev === "sync_click") {
            handleAutoSync();
          }
          else if (ev === "load_track_direct") {
            const t = tracks.find((track: any) => track.id === payload.trackId);
            if (t) loadTrackToDeck(t, payload.deck);
          }
          else if (ev === "trigger_cue_direct") {
            const cues = payload.deck === "A" ? deckACues : deckBCues;
            const val = cues[payload.index - 1];
            if (val !== null) {
              handleSeek(payload.deck, val);
            }
          }
          else if (ev === "nudge_deck_direct") {
            if (payload.deck === "B") {
              nudgeDeckB(payload.direction);
            }
          }
          else if (ev === "sample_loop_direct") {
            const tr = tracks.find((t: any) => t.id === payload.trackId);
            if (tr) {
              sampleLoopFromTrack(tr, payload.padId, payload.beats);
            }
          }
          else if (ev === "automix_action") {
            if (payload.action === "start") {
              startAutomatchicMix();
            } else {
              stopAutomatchicMix();
            }
          }
        }

        else if (type === "clients_updated") {
          setConnectedClients(data.clients || []);
        }
      } catch (err) {
        console.error("[WS Sync] Error routing message:", err);
      }
    };

    ws.onclose = () => {
      setIsWSAconnecting(false);
      setRoomStateSynced(false);
      setConnectedClients([]);
    };

    ws.onerror = () => {
      setIsWSAconnecting(false);
      setRoomStateSynced(false);
    };
  };

  const disconnectFromSyncRoom = () => {
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setRoomStateSynced(false);
    setConnectedClients([]);
    setFeedbackMsg("Disconnected from multi-device session.");
  };

  // Keep-alive timer
  useEffect(() => {
    const keepAlive = setInterval(() => {
      if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
        wsRef.current.send(JSON.stringify({ type: "ping" }));
      }
    }, 20000);
    return () => clearInterval(keepAlive);
  }, []);

  // Canvas Refs for animation
  const canvasARef = useRef<HTMLCanvasElement | null>(null);
  const canvasBRef = useRef<HTMLCanvasElement | null>(null);
  const kaossCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const kaossTrailRef = useRef<{ x: number; y: number; r: number; color: string }[]>([]);

  // Accumulators for elapsed visual position (independent of real clock to support fine speed/phase adjustments)
  const positionRefA = useRef(0);
  const positionRefB = useRef(0);
  const lastBeatRefA = useRef<number>(0);
  const lastBeatRefB = useRef<number>(0);
  const animationFrameRef = useRef<number | null>(null);
  const lastTimeRef = useRef<number>(0);

  // YouTube state and refs
  const playerARef = useRef<any>(null);
  const playerBRef = useRef<any>(null);
  const [playerAReady, setPlayerAReady] = useState(false);
  const [playerBReady, setPlayerBReady] = useState(false);
  const [youtubeIdA, setYoutubeIdA] = useState<string | null>(initialDeckATrack?.youtubeId || null);
  const [youtubeIdB, setYoutubeIdB] = useState<string | null>(initialDeckBTrack?.youtubeId || null);
  const [isSearchingA, setIsSearchingA] = useState(false);
  const [isSearchingB, setIsSearchingB] = useState(false);
  const [showVideoA, setShowVideoA] = useState(true);
  const [showVideoB, setShowVideoB] = useState(true);
  const [customUrlA, setCustomUrlA] = useState("");
  const [customUrlB, setCustomUrlB] = useState("");

  // Inject YouTube script on mount
  useEffect(() => {
    if (!window.YT) {
      const tag = document.createElement("script");
      tag.src = "https://www.youtube.com/iframe_api";
      const firstScriptTag = document.getElementsByTagName("script")[0];
      firstScriptTag.parentNode?.insertBefore(tag, firstScriptTag);
    }
  }, []);

  // Sync loaded tracks when props change
  useEffect(() => {
    if (initialDeckATrack) {
      setDeckA(prev => ({
        ...prev,
        track: initialDeckATrack,
        baseBpm: initialDeckATrack.bpm,
        bpm: initialDeckATrack.bpm * (1 + prev.pitch / 100)
      }));
    }
  }, [initialDeckATrack]);

  useEffect(() => {
    if (initialDeckBTrack) {
      setDeckB(prev => ({
        ...prev,
        track: initialDeckBTrack,
        baseBpm: initialDeckBTrack.bpm,
        bpm: initialDeckBTrack.bpm * (1 + prev.pitch / 100)
      }));
    }
  }, [initialDeckBTrack]);

  // Load session cues and loop settings from URL query parameters on mount
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const cuesAStr = params.get("cuesA");
    const cuesBStr = params.get("cuesB");
    const loopTrackId = params.get("loopTrackId");
    const loopPadIdStr = params.get("loopPadId");
    const loopBeatsStr = params.get("loopBeats");

    if (cuesAStr) {
      try {
        const parsedCuesA = JSON.parse(cuesAStr);
        if (Array.isArray(parsedCuesA)) {
          const normalized = Array(4).fill(null);
          parsedCuesA.forEach((v, idx) => {
            if (idx < 4) normalized[idx] = (v === null || typeof v === "number") ? v : null;
          });
          setDeckACues(normalized);
        }
      } catch (e) {
        console.error("Failed to parse cuesA query param:", e);
      }
    }

    if (cuesBStr) {
      try {
        const parsedCuesB = JSON.parse(cuesBStr);
        if (Array.isArray(parsedCuesB)) {
          const normalized = Array(4).fill(null);
          parsedCuesB.forEach((v, idx) => {
            if (idx < 4) normalized[idx] = (v === null || typeof v === "number") ? v : null;
          });
          setDeckBCues(normalized);
        }
      } catch (e) {
        console.error("Failed to parse cuesB query param:", e);
      }
    }

    if (loopTrackId) {
      setSelectedLoopTrackId(loopTrackId);
    }

    if (loopPadIdStr) {
      const padVal = parseInt(loopPadIdStr, 10);
      if (padVal >= 1 && padVal <= 8) {
        setSelectedLoopPadId(padVal);
      }
    }

    if (loopBeatsStr) {
      const beatsVal = parseInt(loopBeatsStr, 10);
      if ([2, 4, 8, 16].includes(beatsVal)) {
        setSelectedLoopBeats(beatsVal);
      }
    }

    // Auto-synthesize loop if track is loaded and loop parameter exists
    if (loopTrackId) {
      const loopTr = tracks.find(t => t.id === loopTrackId);
      if (loopTr) {
        const padVal = loopPadIdStr ? parseInt(loopPadIdStr, 10) : 5;
        const beatsVal = loopBeatsStr ? parseInt(loopBeatsStr, 10) : 4;
        setTimeout(() => {
          sampleLoopFromTrack(loopTr, padVal, beatsVal);
        }, 1500);
      }
    }
  }, [tracks]);

  // Automated background YouTube Video Lookup
  useEffect(() => {
    if (deckA.track) {
      if (deckA.track.youtubeId) {
        setYoutubeIdA(deckA.track.youtubeId);
      } else {
        setIsSearchingA(true);
        fetch("/api/search-youtube", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title: deckA.track.title, artist: deckA.track.artist })
        })
        .then(res => res.json())
        .then(data => {
          if (data.success && data.videoId) {
            setYoutubeIdA(data.videoId);
            deckA.track!.youtubeId = data.videoId;
          }
        })
        .catch(err => console.error("Error searching YouTube ID for Deck A:", err))
        .finally(() => setIsSearchingA(false));
      }
    } else {
      setYoutubeIdA(null);
      setPlayerAReady(false);
      playerARef.current = null;
    }
  }, [deckA.track?.id]);

  useEffect(() => {
    if (deckB.track) {
      if (deckB.track.youtubeId) {
        setYoutubeIdB(deckB.track.youtubeId);
      } else {
        setIsSearchingB(true);
        fetch("/api/search-youtube", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title: deckB.track.title, artist: deckB.track.artist })
        })
        .then(res => res.json())
        .then(data => {
          if (data.success && data.videoId) {
            setYoutubeIdB(data.videoId);
            deckB.track!.youtubeId = data.videoId;
          }
        })
        .catch(err => console.error("Error searching YouTube ID for Deck B:", err))
        .finally(() => setIsSearchingB(false));
      }
    } else {
      setYoutubeIdB(null);
      setPlayerBReady(false);
      playerBRef.current = null;
    }
  }, [deckB.track?.id]);

  // Instantiate or cue video for YouTube Player A
  useEffect(() => {
    if (!youtubeIdA) return;

    const createPlayerA = () => {
      if (playerARef.current) {
        try {
          playerARef.current.cueVideoById(youtubeIdA);
          return;
        } catch (e) {
          console.warn("Retrying player A creation due to error", e);
          playerARef.current = null;
        }
      }

      if (window.YT && window.YT.Player) {
        const pEl = document.getElementById("youtube-player-deck-a");
        if (!pEl) return;
        playerARef.current = new window.YT.Player("youtube-player-deck-a", {
          height: "100%",
          width: "100%",
          videoId: youtubeIdA,
          playerVars: {
            autoplay: 0,
            controls: 1,
            modestbranding: 1,
            rel: 0,
            origin: window.location.origin
          },
          events: {
            onReady: (e: any) => {
              setPlayerAReady(true);
              e.target.setVolume(deckA.isMuted ? 0 : audioVolume * 100);
              e.target.setPlaybackRate(1 + deckA.pitch / 100);
              if (isPlaying) {
                try { e.target.playVideo(); } catch (err) {}
              }
            }
          }
        });
      }
    };

    if (window.YT && window.YT.Player) {
      createPlayerA();
    } else {
      const interval = setInterval(() => {
        if (window.YT && window.YT.Player) {
          createPlayerA();
          clearInterval(interval);
        }
      }, 500);
      return () => clearInterval(interval);
    }
  }, [youtubeIdA]);

  // Instantiate or cue video for YouTube Player B
  useEffect(() => {
    if (!youtubeIdB) return;

    const createPlayerB = () => {
      if (playerBRef.current) {
        try {
          playerBRef.current.cueVideoById(youtubeIdB);
          return;
        } catch (e) {
          console.warn("Retrying player B creation due to error", e);
          playerBRef.current = null;
        }
      }

      if (window.YT && window.YT.Player) {
        const pEl = document.getElementById("youtube-player-deck-b");
        if (!pEl) return;
        playerBRef.current = new window.YT.Player("youtube-player-deck-b", {
          height: "100%",
          width: "100%",
          videoId: youtubeIdB,
          playerVars: {
            autoplay: 0,
            controls: 1,
            modestbranding: 1,
            rel: 0,
            origin: window.location.origin
          },
          events: {
            onReady: (e: any) => {
              setPlayerBReady(true);
              e.target.setVolume(deckB.isMuted ? 0 : audioVolume * 100);
              e.target.setPlaybackRate(1 + deckB.pitch / 100);
              if (isPlaying) {
                try { e.target.playVideo(); } catch (err) {}
              }
            }
          }
        });
      }
    };

    if (window.YT && window.YT.Player) {
      createPlayerB();
    } else {
      const interval = setInterval(() => {
        if (window.YT && window.YT.Player) {
          createPlayerB();
          clearInterval(interval);
        }
      }, 500);
      return () => clearInterval(interval);
    }
  }, [youtubeIdB]);

  // Sync play/pause commands to YouTube Players
  useEffect(() => {
    if (isPlaying) {
      if (playerAReady && playerARef.current && typeof playerARef.current.playVideo === "function") {
        try { playerARef.current.playVideo(); } catch (err) {}
      }
      if (playerBReady && playerBRef.current && typeof playerBRef.current.playVideo === "function") {
        try { playerBRef.current.playVideo(); } catch (err) {}
      }
    } else {
      if (playerAReady && playerARef.current && typeof playerARef.current.pauseVideo === "function") {
        try { playerARef.current.pauseVideo(); } catch (err) {}
      }
      if (playerBReady && playerBRef.current && typeof playerBRef.current.pauseVideo === "function") {
        try { playerBRef.current.pauseVideo(); } catch (err) {}
      }
    }
  }, [isPlaying, playerAReady, playerBReady]);

  // Sync Pitch Adjustment to Playback Rate
  useEffect(() => {
    const rate = 1 + deckA.pitch / 100;
    if (playerAReady && playerARef.current && typeof playerARef.current.setPlaybackRate === "function") {
      try { playerARef.current.setPlaybackRate(rate); } catch (err) {}
    }
  }, [deckA.pitch, playerAReady]);

  useEffect(() => {
    const rate = 1 + deckB.pitch / 100;
    if (playerBReady && playerBRef.current && typeof playerBRef.current.setPlaybackRate === "function") {
      try { playerBRef.current.setPlaybackRate(rate); } catch (err) {}
    }
  }, [deckB.pitch, playerBReady]);

  const getDeckVolumeA = () => {
    if (deckA.isMuted) return 0;
    if (!isAutoMixing) return audioVolume;
    const factor = Math.max(0, Math.min(1, (100 - autoMixCrossfader) / 200));
    return audioVolume * factor;
  };

  const getDeckVolumeB = () => {
    if (deckB.isMuted) return 0;
    if (!isAutoMixing) return audioVolume;
    const factor = Math.max(0, Math.min(1, (100 + autoMixCrossfader) / 200));
    return audioVolume * factor;
  };

  // Sync volume / mute controls to YouTube Players
  useEffect(() => {
    if (playerAReady && playerARef.current && typeof playerARef.current.setVolume === "function") {
      try { playerARef.current.setVolume(getDeckVolumeA() * 100); } catch (err) {}
    }
  }, [audioVolume, deckA.isMuted, playerAReady, isAutoMixing, autoMixCrossfader]);

  useEffect(() => {
    if (playerBReady && playerBRef.current && typeof playerBRef.current.setVolume === "function") {
      try { playerBRef.current.setVolume(getDeckVolumeB() * 100); } catch (err) {}
    }
  }, [audioVolume, deckB.isMuted, playerBReady, isAutoMixing, autoMixCrossfader]);

  // --- KAOSS / KITARA TOUCH VECTOR CANVAS ANIMATOR ---
  useEffect(() => {
    let animId: number;
    const draw = () => {
      if (activeTab !== "sampler") {
        animId = requestAnimationFrame(draw);
        return;
      }

      const canvas = kaossCanvasRef.current;
      if (!canvas) {
        animId = requestAnimationFrame(draw);
        return;
      }

      const ctx = canvas.getContext("2d");
      if (!ctx) {
        animId = requestAnimationFrame(draw);
        return;
      }

      const width = canvas.width;
      const height = canvas.height;

      // Clear with cumulative transparent black to leave elegant motion trails
      ctx.fillStyle = "rgba(9, 9, 11, 0.22)";
      ctx.fillRect(0, 0, width, height);

      // Draw vector hardware grid patterns
      ctx.strokeStyle = "rgba(168, 85, 247, 0.08)";
      ctx.lineWidth = 1;
      const gridSize = 40;
      for (let x = 0; x < width; x += gridSize) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, height);
        ctx.stroke();
      }
      for (let y = 0; y < height; y += gridSize) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
        ctx.stroke();
      }

      // Cool laser sweeping background radar line
      const sweepLineY = (Date.now() / 12) % height;
      ctx.strokeStyle = "rgba(139, 92, 246, 0.05)";
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.moveTo(0, sweepLineY);
      ctx.lineTo(width, sweepLineY);
      ctx.stroke();

      // Determine glow palette based on selected pad id
      const synthTypes = ["kick", "snare", "hihat", "vocal", "sweep", "bass", "chord", "laser"];
      const activeSynthType = synthTypes[(selectedKaossPadId - 1) % 8];
      const glowColor = activeSynthType === "kick" || activeSynthType === "bass" ? "#f43f5e" :
                        activeSynthType === "snare" || activeSynthType === "sweep" ? "#f97316" :
                        activeSynthType === "hihat" || activeSynthType === "laser" ? "#eab308" : "#c084fc";

      // Render expanding liquid sonar ripples (Kitara style)
      kaossTrailRef.current = kaossTrailRef.current.filter(r => {
        ctx.strokeStyle = r.color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(r.x, r.y, r.r, 0, Math.PI * 2);
        ctx.stroke();

        r.r += 2.0; // expand ring
        return r.r < 95; // keep visible range bounded
      });

      // Draw real-time coordinates, target crosshairs, and pulsing center ring
      if (isKaossActive) {
        const curX = kaossX * width;
        const curY = (1 - kaossY) * height; // cartesian projection

        // Triple-layered neon glowing core
        const coreRadius = 8 + Math.sin(Date.now() / 80) * 3;
        ctx.strokeStyle = glowColor;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(curX, curY, coreRadius, 0, Math.PI * 2);
        ctx.stroke();

        ctx.strokeStyle = `${glowColor}33`; // secondary halo
        ctx.lineWidth = 6;
        ctx.beginPath();
        ctx.arc(curX, curY, coreRadius, 0, Math.PI * 2);
        ctx.stroke();

        // High-contrast coordinates target crosshair
        ctx.strokeStyle = "rgba(255, 255, 255, 0.06)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(curX, 0);
        ctx.lineTo(curX, height);
        ctx.moveTo(0, curY);
        ctx.lineTo(width, curY);
        ctx.stroke();

        // Readout statistics on touch location
        ctx.fillStyle = "rgba(255, 255, 255, 0.75)";
        ctx.font = "bold 9px monospace";
        ctx.fillText(`X: ${Math.round(kaossX * 100)}%`, curX + 15, curY - 5);
        ctx.fillText(`Y: ${Math.round(kaossY * 100)}%`, curX + 15, curY + 7);

        // Periodically record trace ring coordinates to trail
        if (Math.random() < 0.28) {
          kaossTrailRef.current.push({
            x: curX,
            y: curY,
            r: coreRadius,
            color: `${glowColor}66`
          });
        }
      }

      animId = requestAnimationFrame(draw);
    };

    animId = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animId);
  }, [activeTab, isKaossActive, kaossX, kaossY, selectedKaossPadId]);

  // Extract YouTube ID from various URL formats
  const extractVideoId = (urlOrId: string): string | null => {
    const trimmed = urlOrId.trim();
    if (trimmed.length === 11) return trimmed;
    const regExp = /^.*(youtu.be\/|v\/|u\/\w\/|embed\/|watch\?v=|\&v=)([^#\&\?]*).*/;
    const match = trimmed.match(regExp);
    return (match && match[2].length === 11) ? match[2] : null;
  };

  const handleCustomUrlLoad = (deck: "A" | "B") => {
    if (deck === "A") {
      const vidId = extractVideoId(customUrlA);
      if (vidId) {
        setYoutubeIdA(vidId);
        if (deckA.track) {
          deckA.track.youtubeId = vidId;
        }
        setCustomUrlA("");
      }
    } else {
      const vidId = extractVideoId(customUrlB);
      if (vidId) {
        setYoutubeIdB(vidId);
        if (deckB.track) {
          deckB.track.youtubeId = vidId;
        }
        setCustomUrlB("");
      }
    }
  };

  // Audio synthesis for click beats (Web Audio API)
  const initAudioContext = () => {
    if (!audioCtxRef.current) {
      try {
        const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
        audioCtxRef.current = new AudioContextClass();
        setIsAudioContextInitialized(true);
      } catch (e) {
        console.error("Failed to initialize Web Audio Context", e);
      }
    } else if (audioCtxRef.current.state === "suspended") {
      audioCtxRef.current.resume();
      setIsAudioContextInitialized(true);
    }
  };

  const playClickSound = (frequency: number, isDownbeat: boolean) => {
    if (!audioCtxRef.current || audioCtxRef.current.state === "suspended") return;
    
    const osc = audioCtxRef.current.createOscillator();
    const gainNode = audioCtxRef.current.createGain();

    osc.type = isDownbeat ? "triangle" : "sine";
    osc.frequency.setValueAtTime(frequency, audioCtxRef.current.currentTime);
    
    // Quick tick envelope
    gainNode.gain.setValueAtTime(audioVolume, audioCtxRef.current.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.001, audioCtxRef.current.currentTime + 0.08);

    osc.connect(gainNode);
    gainNode.connect(audioCtxRef.current.destination);

    osc.start();
    osc.stop(audioCtxRef.current.currentTime + 0.1);
  };

  // High-fidelity synthesizers and player for Sampler Pads (Web Audio API)
  const playSamplerSound = (pad: SamplerPad, isRemoteEvent = false) => {
    if (!isRemoteEvent) {
      sendWSEvent("play_sampler_pad", { padId: pad.id });
    }
    initAudioContext();
    const ctx = audioCtxRef.current;
    if (!ctx || ctx.state === "suspended") return;

    // Check if it's currently looping and we want to stop it
    if (pad.isLoop && activeLoopSourcesRef.current[pad.id]) {
      try {
        activeLoopSourcesRef.current[pad.id].source.stop();
      } catch (e) {}
      delete activeLoopSourcesRef.current[pad.id];
      setSamplerPads(prev => prev.map(p => p.id === pad.id ? { ...p, isPlaying: false } : p));
      setFeedbackMsg(`Stopped loop on Pad ${pad.id}.`);
      return;
    }

    // Visual pulse feedback on playing state
    setSamplerPads(prev => prev.map(p => p.id === pad.id ? { ...p, isPlaying: true } : p));
    if (!pad.isLoop) {
      setTimeout(() => {
        setSamplerPads(prev => prev.map(p => p.id === pad.id ? { ...p, isPlaying: false } : p));
      }, 400);
    }

    // 1. Play custom recorded buffer if available
    if (pad.recordedBuffer) {
      try {
        const source = ctx.createBufferSource();
        source.buffer = pad.recordedBuffer;
        if (pad.isLoop) {
          source.loop = true;
        }
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(audioVolume * 1.2, ctx.currentTime);
        source.connect(gain);
        gain.connect(ctx.destination);
        source.start();

        if (pad.isLoop) {
          activeLoopSourcesRef.current[pad.id] = { source, gain };
        }
        return;
      } catch (err) {
        console.error("Failed to play custom recorded buffer:", err);
      }
    }

    // 2. Otherwise, synthesize high-quality sound based on assigned synth type
    const now = ctx.currentTime;
    const vol = audioVolume * 1.1;

    switch (pad.synthType) {
      case "kick": {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.frequency.setValueAtTime(140, now);
        osc.frequency.exponentialRampToValueAtTime(0.01, now + 0.28);
        gain.gain.setValueAtTime(vol, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.28);
        osc.start();
        osc.stop(now + 0.3);
        break;
      }
      case "snare": {
        // Snare white noise generator
        const bufferSize = ctx.sampleRate * 0.18;
        const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
          data[i] = Math.random() * 2 - 1;
        }
        const noise = ctx.createBufferSource();
        noise.buffer = buffer;
        const filter = ctx.createBiquadFilter();
        filter.type = "bandpass";
        filter.frequency.value = 1100;
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(vol * 0.7, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.18);
        noise.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        noise.start();
        noise.stop(now + 0.2);

        // Snap body osc
        const snap = ctx.createOscillator();
        const snapGain = ctx.createGain();
        snap.type = "triangle";
        snap.frequency.setValueAtTime(180, now);
        snapGain.gain.setValueAtTime(vol * 0.4, now);
        snapGain.gain.exponentialRampToValueAtTime(0.001, now + 0.08);
        snap.connect(snapGain);
        snapGain.connect(ctx.destination);
        snap.start();
        snap.stop(now + 0.1);
        break;
      }
      case "hihat": {
        const bufferSize = ctx.sampleRate * 0.04;
        const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
          data[i] = Math.random() * 2 - 1;
        }
        const noise = ctx.createBufferSource();
        noise.buffer = buffer;
        const filter = ctx.createBiquadFilter();
        filter.type = "highpass";
        filter.frequency.value = 8500;
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(vol * 0.4, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.04);
        noise.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        noise.start();
        noise.stop(now + 0.05);
        break;
      }
      case "vocal": {
        // High-pitched vocal/formant filter synth
        const osc = ctx.createOscillator();
        osc.type = "sawtooth";
        osc.frequency.setValueAtTime(290, now);
        const filter = ctx.createBiquadFilter();
        filter.type = "bandpass";
        filter.frequency.setValueAtTime(1200, now);
        filter.Q.value = 10;
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(vol * 0.5, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25);
        osc.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(now + 0.3);
        break;
      }
      case "sweep": {
        // Slow atmospheric low-pass filter white noise rise
        const bufferSize = ctx.sampleRate * 1.2;
        const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
          data[i] = Math.random() * 2 - 1;
        }
        const noise = ctx.createBufferSource();
        noise.buffer = buffer;
        const filter = ctx.createBiquadFilter();
        filter.type = "lowpass";
        filter.frequency.setValueAtTime(80, now);
        filter.frequency.exponentialRampToValueAtTime(4500, now + 1.0);
        filter.Q.value = 6;
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(0.001, now);
        gain.gain.linearRampToValueAtTime(vol * 0.3, now + 0.5);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 1.2);
        noise.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        noise.start();
        noise.stop(now + 1.2);
        break;
      }
      case "bass": {
        // Deep pulsating analog bass drop
        const osc = ctx.createOscillator();
        osc.type = "sawtooth";
        osc.frequency.setValueAtTime(65.41, now); // C2 note
        const filter = ctx.createBiquadFilter();
        filter.type = "lowpass";
        filter.frequency.setValueAtTime(320, now);
        filter.frequency.exponentialRampToValueAtTime(40, now + 0.35);
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(vol * 0.85, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.38);
        osc.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(now + 0.4);
        break;
      }
      case "chord": {
        // Warm harmonic digital synth triad chord (E Minor triad)
        const freqs = [164.81, 196.00, 246.94]; // E3, G3, B3
        freqs.forEach(freq => {
          const osc = ctx.createOscillator();
          osc.type = "triangle";
          osc.frequency.setValueAtTime(freq, now);
          const gain = ctx.createGain();
          gain.gain.setValueAtTime(vol * 0.22, now);
          gain.gain.exponentialRampToValueAtTime(0.001, now + 0.55);
          osc.connect(gain);
          gain.connect(ctx.destination);
          osc.start();
          osc.stop(now + 0.6);
        });
        break;
      }
      case "laser": {
        // Vintage arcade laser sweep
        const osc = ctx.createOscillator();
        osc.type = "sawtooth";
        osc.frequency.setValueAtTime(1400, now);
        osc.frequency.exponentialRampToValueAtTime(110, now + 0.32);
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(vol * 0.4, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.32);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(now + 0.35);
        break;
      }
    }
  };

  // --- KAOSS PAD / MISA KITARA DYNAMIC AUDIO ENGINE ---
  const startKaossSound = (pad: SamplerPad, x: number, y: number) => {
    initAudioContext();
    const ctx = audioCtxRef.current;
    if (!ctx) return;
    
    // Stop any existing Kaoss sound to avoid leaks
    stopKaossSound();

    const now = ctx.currentTime;
    const vol = audioVolume * 1.25;

    // Create main nodes
    const mainGain = ctx.createGain();
    mainGain.gain.setValueAtTime(vol, now);

    const filter = ctx.createBiquadFilter();
    filter.type = "lowpass";
    filter.frequency.setValueAtTime(1000, now);
    filter.Q.setValueAtTime(8, now);

    let oscs: OscillatorNode[] = [];
    let noise: AudioBufferSourceNode | undefined = undefined;
    let waveShaper: WaveShaperNode | undefined = undefined;

    // Setup delay node if FX is active
    let delay: DelayNode | undefined = undefined;
    let feedbackGain: GainNode | undefined = undefined;

    if (kaossFxType === "delay") {
      delay = ctx.createDelay();
      feedbackGain = ctx.createGain();
      
      // Delay parameters
      delay.delayTime.setValueAtTime(0.25, now);
      feedbackGain.gain.setValueAtTime(0.45, now);

      // Connect loop: Filter -> Delay -> FeedbackGain -> Delay
      filter.connect(delay);
      delay.connect(feedbackGain);
      feedbackGain.connect(delay);

      // Connect both dry (filter) and wet (delay) to main gain
      filter.connect(mainGain);
      delay.connect(mainGain);
    } else {
      filter.connect(mainGain);
    }

    mainGain.connect(ctx.destination);

    // Instrument specific setups:
    if (pad.synthType === "kick" || pad.synthType === "bass") {
      // Detuned dual oscillator sub-bass wobble
      const osc1 = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      osc1.type = "sawtooth";
      osc2.type = "sawtooth";
      
      osc1.connect(filter);
      osc2.connect(filter);
      
      osc1.start(now);
      osc2.start(now);
      oscs = [osc1, osc2];
      
    } else if (pad.synthType === "snare" || pad.synthType === "sweep") {
      // Procedural white noise synthesizer with sweeping cutoff filter
      const bufferSize = ctx.sampleRate * 2.0; // long buffer for continuous dragging
      const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
      const data = buffer.getChannelData(0);
      for (let i = 0; i < bufferSize; i++) {
        data[i] = Math.random() * 2 - 1;
      }
      noise = ctx.createBufferSource();
      noise.buffer = buffer;
      noise.loop = true;
      
      noise.connect(filter);
      noise.start(now);
      
    } else if (pad.synthType === "hihat" || pad.synthType === "laser") {
      // Laser arcade frequency sweeping synth
      const osc = ctx.createOscillator();
      osc.type = "sine";
      osc.connect(filter);
      osc.start(now);
      oscs = [osc];
      
    } else if (pad.synthType === "vocal") {
      // Formant simulation vocal dual oscillators
      const osc = ctx.createOscillator();
      osc.type = "sawtooth";
      
      // Waveshaper for tube saturation
      waveShaper = ctx.createWaveShaper();
      // Simple distortion curve
      const makeDistortionCurve = (amount = 20) => {
        const k = typeof amount === 'number' ? amount : 50;
        const n_samples = 44100;
        const curve = new Float32Array(n_samples);
        const deg = Math.PI / 180;
        for (let i = 0; i < n_samples; ++i) {
          const x2 = (i * 2) / n_samples - 1;
          curve[i] = ((3 + k) * x2 * 20 * deg) / (Math.PI + k * Math.abs(x2));
        }
        return curve;
      };
      waveShaper.curve = makeDistortionCurve(30);
      waveShaper.oversample = "4x";

      osc.connect(waveShaper);
      waveShaper.connect(filter);
      osc.start(now);
      oscs = [osc];
      
    } else if (pad.synthType === "chord") {
      // Kitara chord synthesizer: playing a full C-Minor, F-Minor, G-Major, or G-Minor triad
      const baseFreqs = [130.81, 155.56, 196.00];
      baseFreqs.forEach(freq => {
        const osc = ctx.createOscillator();
        osc.type = "triangle";
        osc.connect(filter);
        osc.start(now);
        oscs.push(osc);
      });
    }

    // Save playing nodes
    kaossAudioRef.current = {
      oscs,
      filter,
      gain: mainGain,
      noise,
      delay,
      feedbackGain,
      waveShaper
    };

    // Update frequencies immediately based on initial x, y
    updateKaossSound(pad, x, y);
  };

  const updateKaossSound = (pad: SamplerPad, x: number, y: number) => {
    const ctx = audioCtxRef.current;
    const nodes = kaossAudioRef.current;
    if (!ctx || !nodes) return;

    const now = ctx.currentTime;

    // Apply X and Y coordinates to modulate cutoff and pitches on-the-fly!
    if (pad.synthType === "kick" || pad.synthType === "bass") {
      // Bass wobble: 
      // X maps to Lowpass Filter frequency: 60Hz to 1500Hz
      // Y maps to pitch frequency: 40Hz to 180Hz
      const cutoff = Math.pow(x, 1.5) * 1440 + 60; // exponential feel
      const freq = y * 140 + 40;

      if (nodes.filter) {
        nodes.filter.type = "lowpass";
        nodes.filter.frequency.setTargetAtTime(cutoff, now, 0.04);
        nodes.filter.Q.setTargetAtTime(10 + y * 8, now, 0.04);
      }

      if (nodes.oscs && nodes.oscs.length >= 2) {
        nodes.oscs[0].frequency.setTargetAtTime(freq, now, 0.05);
        nodes.oscs[1].frequency.setTargetAtTime(freq + 3.5, now, 0.05); // Detuning
      }
    } 
    else if (pad.synthType === "snare" || pad.synthType === "sweep") {
      // Noise Sweeper:
      // X maps to Bandpass frequency: 150Hz to 6000Hz
      // Y maps to resonance (Q): 1 to 25
      const cutoff = Math.pow(x, 1.2) * 5850 + 150;
      const Q = y * 24 + 1.0;

      if (nodes.filter) {
        nodes.filter.type = "bandpass";
        nodes.filter.frequency.setTargetAtTime(cutoff, now, 0.05);
        nodes.filter.Q.setTargetAtTime(Q, now, 0.05);
      }
    } 
    else if (pad.synthType === "hihat" || pad.synthType === "laser") {
      // Laser / Hi-Hat Sweep:
      // X maps to Highpass filter cutoff: 1000Hz to 10000Hz
      // Y maps to sine oscillator frequency: 120Hz to 2400Hz
      const cutoff = x * 9000 + 1000;
      const freq = Math.pow(y, 1.5) * 2280 + 120;

      if (nodes.filter) {
        nodes.filter.type = "highpass";
        nodes.filter.frequency.setTargetAtTime(cutoff, now, 0.04);
      }

      if (nodes.oscs && nodes.oscs.length > 0) {
        nodes.oscs[0].frequency.setTargetAtTime(freq, now, 0.04);
      }
    } 
    else if (pad.synthType === "vocal") {
      // Vocal Sweeper:
      // X maps to fundamental oscillator frequency: 110Hz to 440Hz
      // Y maps to bandpass formant frequency: 400Hz to 3200Hz
      const freq = x * 330 + 110;
      const formantCutoff = Math.pow(y, 1.2) * 2800 + 400;

      if (nodes.filter) {
        nodes.filter.type = "bandpass";
        nodes.filter.frequency.setTargetAtTime(formantCutoff, now, 0.06);
        nodes.filter.Q.setTargetAtTime(12, now, 0.06);
      }

      if (nodes.oscs && nodes.oscs.length > 0) {
        nodes.oscs[0].frequency.setTargetAtTime(freq, now, 0.05);
      }
    } 
    else if (pad.synthType === "chord") {
      // Kitara Chord Synth:
      // X maps to chord transposition / root frequency (e.g., C minor up to G minor)
      // Y maps to high cutoff sweep filter: 200Hz to 4000Hz
      const baseFreqs = [130.81, 155.56, 196.00]; // C minor triad: C3, Eb3, G3
      const pitchTransposeMultiplier = Math.pow(2, Math.floor(x * 12) / 12); // Quantized chromatic steps!
      const cutoff = y * 3800 + 200;

      if (nodes.filter) {
        nodes.filter.type = "lowpass";
        nodes.filter.frequency.setTargetAtTime(cutoff, now, 0.05);
      }

      if (nodes.oscs && nodes.oscs.length >= 3) {
        nodes.oscs[0].frequency.setTargetAtTime(baseFreqs[0] * pitchTransposeMultiplier, now, 0.06);
        nodes.oscs[1].frequency.setTargetAtTime(baseFreqs[1] * pitchTransposeMultiplier, now, 0.06);
        nodes.oscs[2].frequency.setTargetAtTime(baseFreqs[2] * pitchTransposeMultiplier, now, 0.06);
      }
    }

    // Live-modulate Delay FX on X & Y if active
    if (nodes.delay && nodes.feedbackGain) {
      // X maps to delay feedback amount: 0.1 to 0.85
      // Y maps to delay echo speed: 0.05s to 0.75s
      const delayTime = y * 0.70 + 0.05;
      const feedback = x * 0.75 + 0.1;
      nodes.delay.delayTime.setTargetAtTime(delayTime, now, 0.1);
      nodes.feedbackGain.gain.setTargetAtTime(feedback, now, 0.08);
    }
  };

  const stopKaossSound = () => {
    const nodes = kaossAudioRef.current;
    if (!nodes) return;

    const ctx = audioCtxRef.current;
    const now = ctx ? ctx.currentTime : 0;

    // Smooth exponentially decaying release to prevent clicks
    if (nodes.gain && ctx) {
      try {
        nodes.gain.gain.cancelScheduledValues(now);
        nodes.gain.gain.setValueAtTime(nodes.gain.gain.value, now);
        nodes.gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.15);
      } catch (err) {}
    }

    // Scheduled stop for safety
    setTimeout(() => {
      try {
        if (nodes.oscs) {
          nodes.oscs.forEach(osc => {
            try { osc.stop(); } catch (e) {}
            try { osc.disconnect(); } catch (e) {}
          });
        }
        if (nodes.noise) {
          try { nodes.noise.stop(); } catch (e) {}
          try { nodes.noise.disconnect(); } catch (e) {}
        }
        if (nodes.filter) {
          try { nodes.filter.disconnect(); } catch (e) {}
        }
        if (nodes.delay) {
          try { nodes.delay.disconnect(); } catch (e) {}
        }
        if (nodes.feedbackGain) {
          try { nodes.feedbackGain.disconnect(); } catch (e) {}
        }
        if (nodes.waveShaper) {
          try { nodes.waveShaper.disconnect(); } catch (e) {}
        }
        if (nodes.gain) {
          try { nodes.gain.disconnect(); } catch (e) {}
        }
      } catch (e) {}
    }, 180);

    if (nodes.stutterInterval) {
      clearInterval(nodes.stutterInterval);
    }

    kaossAudioRef.current = null;
  };

  // --- KAOSS POINTER COORDINATE EVENT HANDLERS ---
  const handleKaossPointerDown = (e: React.MouseEvent<HTMLDivElement> | React.TouchEvent<HTMLDivElement>) => {
    e.preventDefault();
    initAudioContext();
    const rect = e.currentTarget.getBoundingClientRect();
    let clientX = 0;
    let clientY = 0;
    if ("touches" in e) {
      if (e.touches.length === 0) return;
      clientX = e.touches[0].clientX;
      clientY = e.touches[0].clientY;
    } else {
      clientX = e.clientX;
      clientY = e.clientY;
    }
    const rawX = (clientX - rect.left) / rect.width;
    const rawY = 1 - (clientY - rect.top) / rect.height; // Cartesian upward projection
    const x = Math.max(0, Math.min(1, rawX));
    const y = Math.max(0, Math.min(1, rawY));
    
    setKaossX(x);
    setKaossY(y);
    setIsKaossActive(true);

    const pad = samplerPads.find(p => p.id === selectedKaossPadId) || samplerPads[0];
    startKaossSound(pad, x, y);
    sendWSEvent("kaoss_move", { x, y, color: "rgba(168, 85, 247, 0.75)" });
  };

  const handleKaossPointerMove = (e: React.MouseEvent<HTMLDivElement> | React.TouchEvent<HTMLDivElement>) => {
    if (!isKaossActive) return;
    const rect = e.currentTarget.getBoundingClientRect();
    let clientX = 0;
    let clientY = 0;
    if ("touches" in e) {
      if (e.touches.length === 0) return;
      clientX = e.touches[0].clientX;
      clientY = e.touches[0].clientY;
    } else {
      clientX = e.clientX;
      clientY = e.clientY;
    }
    const rawX = (clientX - rect.left) / rect.width;
    const rawY = 1 - (clientY - rect.top) / rect.height;
    const x = Math.max(0, Math.min(1, rawX));
    const y = Math.max(0, Math.min(1, rawY));

    setKaossX(x);
    setKaossY(y);

    const pad = samplerPads.find(p => p.id === selectedKaossPadId) || samplerPads[0];
    updateKaossSound(pad, x, y);
    sendWSEvent("kaoss_move", { x, y, color: "rgba(6, 182, 212, 0.75)" });
  };

  const handleKaossPointerUp = () => {
    if (!isKaossActive) return;
    setIsKaossActive(false);
    stopKaossSound();
  };

  // Record audio into a Sampler Pad
  const startRecordingSample = async (padId: number) => {
    initAudioContext();
    setFeedbackMsg("");
    
    // Stop any active recording
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== "inactive") {
      stopRecordingSample();
      return;
    }

    try {
      // 1. Request actual Microphone permissions
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunksRef.current = [];
      recordingPadIdRef.current = padId;

      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = async () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: "audio/webm" });
        const arrayBuffer = await audioBlob.arrayBuffer();
        
        if (audioCtxRef.current) {
          audioCtxRef.current.decodeAudioData(arrayBuffer, (decodedBuffer) => {
            setSamplerPads(prev => prev.map(p => p.id === padId ? {
              ...p,
              name: `Voice Sample #${padId}`,
              isAssigned: true,
              isRecording: false,
              recordedBuffer: decodedBuffer
            } : p));
            setFeedbackMsg(`Successfully recorded microphone audio directly into Pad ${padId}!`);
          }, (err) => {
            console.error("Error decoding custom recorded audio data", err);
            generateMockRecordingFallback(padId);
          });
        }

        // Clean up mic track stream lines
        stream.getTracks().forEach(track => track.stop());
      };

      // Set recording visual state
      setSamplerPads(prev => prev.map(p => p.id === padId ? { ...p, isRecording: true } : p));
      mediaRecorder.start();
      setFeedbackMsg(`Recording live mic audio into Pad ${padId}... Press "Stop" to finalize.`);

      // Automatically stop recording after 3 seconds max limit
      setTimeout(() => {
        if (mediaRecorderRef.current && mediaRecorderRef.current.state === "recording" && recordingPadIdRef.current === padId) {
          stopRecordingSample();
        }
      }, 3500);

    } catch (err) {
      console.warn("Microphone access denied or unavailable. Falling back to dynamic Mix-Grab loop generation.", err);
      // fallback
      generateMockRecordingFallback(padId);
    }
  };

  const stopRecordingSample = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === "recording") {
      mediaRecorderRef.current.stop();
      mediaRecorderRef.current = null;
    }
  };

  // Fallback to "Mix Grab" loop generator - Synthesizes a beautiful rhythmic beat loop based on active deck BPM!
  const generateMockRecordingFallback = (padId: number) => {
    initAudioContext();
    const ctx = audioCtxRef.current;
    if (!ctx) return;

    setFeedbackMsg("Connecting live console stream... Grabbing clean visual audio transients...");
    
    // Set recording feedback briefly
    setSamplerPads(prev => prev.map(p => p.id === padId ? { ...p, isRecording: true } : p));

    setTimeout(() => {
      // Create a 1.2 second procedural rhythmic loop matching current speed
      const bufferRate = ctx.sampleRate;
      const durationSeconds = 1.2;
      const totalSamples = bufferRate * durationSeconds;
      const buffer = ctx.createBuffer(1, totalSamples, bufferRate);
      const data = buffer.getChannelData(0);

      const bpm = (deckA.bpm + deckB.bpm) / 2;
      const beatLength = 60 / bpm; // duration of 1 beat in seconds

      // Fill buffer with cool sci-fi techno loop beats
      for (let i = 0; i < totalSamples; i++) {
        const t = i / bufferRate;
        
        // 1. Procedural deep sub kick on downbeats (t=0, t=beatLength)
        let kickEnvelope = 0;
        const tKick = t % beatLength;
        if (tKick < 0.2) {
          const freq = 120 * Math.exp(-tKick * 20);
          kickEnvelope = Math.sin(2 * Math.PI * freq * tKick) * Math.exp(-tKick * 8);
        }

        // 2. Procedural white noise snare clap offbeats (t=beatLength/2)
        let snareEnvelope = 0;
        const tSnare = (t + beatLength / 2) % beatLength;
        if (tSnare < 0.15) {
          snareEnvelope = (Math.random() * 2 - 1) * Math.exp(-tSnare * 15) * 0.45;
        }

        // 3. Ambient resonant synth rise
        const synthFreq = 220 + 220 * (t / durationSeconds);
        const synthVibe = Math.sin(2 * Math.PI * synthFreq * t) * 0.15;

        data[i] = (kickEnvelope + snareEnvelope + synthVibe) * 0.65;
      }

      setSamplerPads(prev => prev.map(p => p.id === padId ? {
        ...p,
        name: `Mix Grab Loop ${Math.round(bpm)}BPM`,
        isAssigned: true,
        isRecording: false,
        recordedBuffer: buffer,
        color: "from-cyan-500/15 to-purple-500/15 border-cyan-500/50 text-cyan-300 hover:bg-cyan-500/25"
      } : p));

      setFeedbackMsg(`Successfully grabbed & synthesized a 1.2-second loop from your mixing console into Pad ${padId}!`);
    }, 1200);
  };

  // Automatically grab atmospheric and drum samples to populate all unused sampler pads
  const triggerAutoGrab = () => {
    initAudioContext();
    const bpm = Math.round((deckA.bpm + deckB.bpm) / 2);
    
    // Custom names and synths tailored to tracks loaded
    const trackAAtmosphere = deckA.track?.atmosphere || "warm, groovy";
    const trackBGenre = deckB.track?.genres?.[0] || "House";

    const customPadsData = [
      { id: 5, name: `${trackBGenre} Bass Pulse`, color: "from-emerald-500/15 to-emerald-500/25 border-emerald-500/50 text-emerald-400 hover:bg-emerald-500/35", synthType: "bass" },
      { id: 6, name: "Chill Sweep Rise", color: "from-blue-500/15 to-blue-500/25 border-blue-500/50 text-blue-400 hover:bg-blue-500/35", synthType: "sweep" },
      { id: 7, name: "Digital Chord Stab", color: "from-cyan-500/15 to-cyan-500/25 border-cyan-500/50 text-cyan-400 hover:bg-cyan-500/35", synthType: "chord" },
      { id: 8, name: "Retro Laser Echo", color: "from-pink-500/15 to-pink-500/25 border-pink-500/50 text-pink-400 hover:bg-pink-500/35", synthType: "laser" }
    ];

    setSamplerPads(prev => prev.map(pad => {
      if (!pad.isAssigned) {
        const grabMatch = customPadsData.find(g => g.id === pad.id);
        if (grabMatch) {
          return {
            ...pad,
            name: grabMatch.name,
            color: grabMatch.color,
            synthType: grabMatch.synthType as any,
            isAssigned: true
          };
        }
      }
      return pad;
    }));

    setFeedbackMsg(`Auto Grab Complete! Analyzed atmosphere "${trackAAtmosphere}" and populated remaining pads at ${bpm} BPM.`);
  };

  // --- SEGMENTS, SEEKING, AND SYNTHESIZERS FOR AUTOMATIC LOOP MAKER ---
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const handleSeek = (deck: "A" | "B", timeSeconds: number) => {
    initAudioContext();
    const duration = deck === "A" ? durationA : durationB;
    const time = Math.max(0, Math.min(duration, timeSeconds));
    if (deck === "A") {
      if (playerARef.current && typeof playerARef.current.seekTo === "function" && playerAReady) {
        playerARef.current.seekTo(time);
        setTimeA(time);
      }
      const bpm = deckA.bpm || 120;
      positionRefA.current = time * (bpm / 60);
      sendWSState({ deckA: { currentTime: time } });
    } else {
      if (playerBRef.current && typeof playerBRef.current.seekTo === "function" && playerBReady) {
        playerBRef.current.seekTo(time);
        setTimeB(time);
      }
      const bpm = deckB.bpm || 120;
      positionRefB.current = time * (bpm / 60);
      sendWSState({ deckB: { currentTime: time } });
    }
    setFeedbackMsg(`Seeked Deck ${deck} to ${formatTime(time)}.`);
  };

  const getSongElements = (track: Track, duration: number) => {
    if (!track) return [];
    const bpm = track.bpm || 120;
    const isHighEnergy = (track.energyLevel || 7) >= 7;
    const atmosphericType = track.atmosphere || "groovy";
    
    return [
      { 
        name: "Drum Intro", 
        start: 0, 
        end: Math.round(duration * 0.1), 
        color: "bg-cyan-500", 
        border: "border-cyan-400",
        desc: "Clean beat grid. Excellent zone for intro blending." 
      },
      { 
        name: atmosphericType.split(",")[0] + " Build", 
        start: Math.round(duration * 0.1), 
        end: Math.round(duration * 0.3), 
        color: "bg-purple-500", 
        border: "border-purple-400",
        desc: "Melodic sweeps, pads swell, vocals enter." 
      },
      { 
        name: "Snare Roll / Sweep", 
        start: Math.round(duration * 0.3), 
        end: Math.round(duration * 0.45), 
        color: "bg-pink-500", 
        border: "border-pink-400",
        desc: "Tension riser zone. Filter cutoff sweep active." 
      },
      { 
        name: isHighEnergy ? "⚡ Peak Drop" : "✨ Main Vibe", 
        start: Math.round(duration * 0.45), 
        end: Math.round(duration * 0.75), 
        color: "bg-red-500", 
        border: "border-red-400",
        desc: "Full bass drop & core groove signature." 
      },
      { 
        name: "Outro Outbound", 
        start: Math.round(duration * 0.75), 
        end: duration, 
        color: "bg-yellow-500", 
        border: "border-yellow-400",
        desc: "Drum outro. Clean blend zone to exit track safely." 
      }
    ];
  };

  const sampleLoopFromTrack = (track: Track, targetPadId: number, beats: number) => {
    initAudioContext();
    const ctx = audioCtxRef.current;
    if (!ctx) return;

    setFeedbackMsg(`Initiating Loop Sampling for "${track.title}"... Analyzing transient models...`);
    
    // Set target pad visual recording state
    setSamplerPads(prev => prev.map(p => p.id === targetPadId ? { ...p, isRecording: true } : p));

    setTimeout(() => {
      const bufferRate = ctx.sampleRate;
      const bpm = track.bpm || 120;
      const beatDuration = 60 / bpm;
      const durationSeconds = beatDuration * beats;
      const totalSamples = Math.round(bufferRate * durationSeconds);
      
      const buffer = ctx.createBuffer(2, totalSamples, bufferRate); // Stereo buffer for lush spacious sounds!
      const leftChannel = buffer.getChannelData(0);
      const rightChannel = buffer.getChannelData(1);

      // Procedural synthesizers customized based on Track characteristics:
      const genresStr = (track.genres || []).join(" ").toLowerCase();
      const atmosphereStr = (track.atmosphere || "").toLowerCase();
      const energy = track.energyLevel || 7;
      
      // Determine style archetype: house, techno, dubstep, dnb, hiphop, default
      let style: "house" | "techno" | "dubstep" | "dnb" | "lofi" | "trance" | "electro" = "house";
      if (genresStr.includes("techno") || atmosphereStr.includes("industrial") || atmosphereStr.includes("dark")) {
        style = "techno";
      } else if (genresStr.includes("dubstep") || genresStr.includes("bass") || atmosphereStr.includes("growl") || atmosphereStr.includes("heavy")) {
        style = "dubstep";
      } else if (genresStr.includes("drum") || genresStr.includes("dnb") || genresStr.includes("breakbeat")) {
        style = "dnb";
      } else if (genresStr.includes("lofi") || genresStr.includes("chill") || atmosphereStr.includes("lounge") || atmosphereStr.includes("dusty")) {
        style = "lofi";
      } else if (genresStr.includes("trance") || atmosphereStr.includes("euphoric") || atmosphereStr.includes("uplifting")) {
        style = "trance";
      } else if (genresStr.includes("electro") || genresStr.includes("synth") || atmosphereStr.includes("retro")) {
        style = "electro";
      }

      // Map Camelot key to standard frequency
      const keyFrequencies: Record<string, number> = {
        "8A": 110.00, // Am (A2)
        "8B": 130.81, // C (C3)
        "9A": 146.83, // Em
        "9B": 196.00, // G (G3)
        "10A": 130.81, // Bm
        "10B": 146.83, // D (D3)
        "11A": 185.00, // F#m
        "11B": 220.00, // A (A3)
        "12A": 138.59, // C#m
        "12B": 164.81, // E
        "1A": 207.65,  // G#m
        "1B": 246.94,  // B
        "2A": 116.54,  // D#m
        "2B": 138.59,  // F#
        "3A": 116.54,  // Bbm
        "3B": 130.81,  // Db
        "4A": 174.61,  // Fm
        "4B": 207.65,  // Ab
        "5A": 196.00,  // Cm
        "5B": 233.08,  // Eb
        "6A": 146.83,  // Gm
        "6B": 174.61,  // Bb
        "7A": 146.83,  // Dm
        "7B": 164.81,  // F
      };
      
      const rootFreq = keyFrequencies[track.camelotKey.toUpperCase()] || 110.00;

      // Fill buffers with custom audio signals
      for (let i = 0; i < totalSamples; i++) {
        const t = i / bufferRate;
        const beatPosition = t / beatDuration; // beat index in loop (0 to beats)
        
        let signalL = 0;
        let signalR = 0;

        // --- LAYER 1: THE BEAT RHYTHM ---
        if (style === "house" || style === "techno" || style === "trance" || style === "electro") {
          // 4-on-the-floor Kick
          const tKick = t % beatDuration;
          if (tKick < 0.25) {
            const fKick = 130 * Math.exp(-tKick * 35);
            const ampKick = Math.sin(2 * Math.PI * fKick * tKick) * Math.exp(-tKick * 10) * (energy / 10);
            signalL += ampKick * 0.7;
            signalR += ampKick * 0.7;
          }
          // Offbeat Hihats
          const tHat = (t + beatDuration / 2) % beatDuration;
          if (tHat < 0.05) {
            const hN = Math.random() * 2 - 1;
            const ampHat = hN * Math.exp(-tHat * 45) * 0.22;
            signalL += ampHat;
            signalR += ampHat;
          }
          // Snare on beat 2 and 4
          const beatInt = Math.floor(beatPosition) % 4;
          if (beatInt === 1 || beatInt === 3) {
            const tSnare = t % beatDuration;
            if (tSnare < 0.16) {
              const noiseSnare = (Math.random() * 2 - 1) * Math.exp(-tSnare * 18) * 0.26;
              const toneSnare = Math.sin(2 * Math.PI * 180 * tSnare) * Math.exp(-tSnare * 12) * 0.18;
              signalL += noiseSnare + toneSnare;
              signalR += noiseSnare + toneSnare;
            }
          }
        } 
        else if (style === "dubstep" || style === "lofi" || style === "dnb") {
          const modValue = style === "dnb" ? 2 : 4;
          const subBeat = beatPosition % modValue;
          
          const isKickStep = subBeat < 0.15 || (style === "dnb" && Math.abs(subBeat - 1.25) < 0.15) || (style !== "dnb" && Math.abs(subBeat - 2.5) < 0.15);
          if (isKickStep) {
            const tKick = (t % beatDuration);
            const fKick = (style === "dnb" ? 140 : 100) * Math.exp(-tKick * 25);
            const ampKick = Math.sin(2 * Math.PI * fKick * tKick) * Math.exp(-tKick * 8) * (energy / 10) * 0.7;
            signalL += ampKick;
            signalR += ampKick;
          }

          const snareStep = style === "dnb" ? 1 : 2;
          if (Math.floor(subBeat) === snareStep) {
            const tSnare = t % beatDuration;
            if (tSnare < 0.2) {
              const noiseSnare = (Math.random() * 2 - 1) * Math.exp(-tSnare * 14) * 0.35;
              const toneSnare = Math.sin(2 * Math.PI * (style === "lofi" ? 140 : 160) * tSnare) * Math.exp(-tSnare * 10) * 0.15;
              signalL += noiseSnare + toneSnare;
              signalR += noiseSnare + toneSnare;
            }
          }

          const tShaker = (t % (beatDuration / 2));
          if (tShaker < 0.04) {
            const shakerAmp = (Math.random() * 2 - 1) * Math.exp(-tShaker * 50) * (style === "lofi" ? 0.1 : 0.18);
            signalL += shakerAmp * 0.8;
            signalR += shakerAmp * 0.4;
          }
        }

        // --- LAYER 2: MELODIC BASSLINE & CHORDS ---
        const step = Math.floor(beatPosition * 2) % 8;
        const stepPitches = [1.0, 1.2, 1.5, 1.0, 1.33, 1.5, 1.8, 1.5];
        const stepMultiplier = stepPitches[step];
        const currentFreq = rootFreq * stepMultiplier;

        if (style === "house") {
          if (step === 0 || step === 3 || step === 5) {
            const tStep = (t % (beatDuration / 2));
            if (tStep < 0.18) {
              const bassSub = Math.sin(2 * Math.PI * (currentFreq / 2) * tStep) * Math.exp(-tStep * 10) * 0.32;
              const organTone = Math.sin(2 * Math.PI * currentFreq * tStep) * Math.exp(-tStep * 8) * 0.15;
              signalL += bassSub + organTone;
              signalR += bassSub + organTone;
            }
          }
        }
        else if (style === "techno") {
          const tWave = t % durationSeconds;
          const filterCutoff = 120 + 400 * Math.sin(2 * Math.PI * (1 / durationSeconds) * tWave);
          const saw = Math.sin(2 * Math.PI * (rootFreq / 2) * tWave) * 0.25;
          const softSaw = saw * Math.min(1, filterCutoff / 500);
          signalL += softSaw;
          signalR += softSaw;
        }
        else if (style === "dubstep") {
          const wobbleSpeed = 4 + 4 * Math.sin(2 * Math.PI * (1 / durationSeconds) * t);
          const lfo = 0.5 + 0.5 * Math.sin(2 * Math.PI * wobbleSpeed * t);
          const saw1 = Math.sin(2 * Math.PI * (rootFreq / 2) * t);
          const saw2 = Math.sin(2 * Math.PI * (rootFreq / 2 + 2) * t);
          const wobbleBass = (saw1 + saw2) * lfo * 0.32;
          signalL += wobbleBass;
          signalR += wobbleBass;
        }
        else if (style === "dnb") {
          const saw1 = Math.sin(2 * Math.PI * (rootFreq / 2) * t);
          const saw2 = Math.sin(2 * Math.PI * (rootFreq / 2 * 1.01) * t);
          const reese = (saw1 + saw2) * 0.22;
          signalL += reese * 0.7;
          signalR += reese * 0.7;
        }
        else if (style === "lofi") {
          const freqs = [rootFreq, rootFreq * 1.2, rootFreq * 1.5, rootFreq * 1.8];
          const tLofi = t % durationSeconds;
          freqs.forEach((f, idx) => {
            const lfo = 0.8 + 0.2 * Math.sin(2 * Math.PI * 3 * tLofi);
            const note = Math.sin(2 * Math.PI * f * tLofi) * Math.exp(-tLofi * 2) * 0.08 * lfo;
            if (idx % 2 === 0) signalL += note;
            else signalR += note;
          });
          if (Math.random() < 0.03) {
            const crackle = (Math.random() * 2 - 1) * 0.04;
            signalL += crackle;
            signalR += crackle;
          }
        }
        else if (style === "trance") {
          const arpStep = Math.floor(beatPosition * 4) % 16;
          const arpMultipliers = [1, 1.2, 1.5, 1.2, 1.8, 1.5, 2, 1.5, 1.33, 1.5, 1.8, 1.5, 1.2, 1, 1.2, 1.5];
          const arpFreq = rootFreq * arpMultipliers[arpStep];
          const tArp = t % (beatDuration / 4);
          if (tArp < 0.12) {
            const arpTone = Math.sin(2 * Math.PI * arpFreq * tArp) * Math.exp(-tArp * 12) * 0.15;
            if (arpStep % 2 === 0) {
              signalL += arpTone * 0.85;
              signalR += arpTone * 0.15;
            } else {
              signalL += arpTone * 0.15;
              signalR += arpTone * 0.85;
            }
          }
        }
        else if (style === "electro") {
          const tChiptune = t % (beatDuration / 2);
          const fChiptune = currentFreq * (1 + 0.1 * Math.sin(2 * Math.PI * 6 * tChiptune));
          const sqr = (Math.sin(2 * Math.PI * fChiptune * tChiptune) >= 0 ? 1 : -1) * 0.06;
          signalL += sqr;
          signalR += sqr;
        }

        leftChannel[i] = Math.max(-0.95, Math.min(0.95, signalL));
        rightChannel[i] = Math.max(-0.95, Math.min(0.95, signalR));
      }

      setSamplerPads(prev => prev.map(p => p.id === targetPadId ? {
        ...p,
        name: `Loop: ${track.title.slice(0, 14)}`,
        isAssigned: true,
        isRecording: false,
        recordedBuffer: buffer,
        isLoop: true,
        color: "from-purple-500/20 to-cyan-500/20 border-purple-400 shadow-purple-950/25 text-purple-300 hover:bg-purple-500/35"
      } : p));

      setFeedbackMsg(`Successfully Synthesized & Loaded a ${beats}-beat ${style.toUpperCase()} Loop for "${track.title}" on Pad ${targetPadId}!`);
    }, 1500);
  };

  // Periodically query actual playing times and durations from YouTube Player APIs
  useEffect(() => {
    const timer = setInterval(() => {
      if (playerARef.current && typeof playerARef.current.getCurrentTime === "function" && playerAReady) {
        try {
          const t = playerARef.current.getCurrentTime();
          const d = playerARef.current.getDuration() || 180;
          setTimeA(t);
          setDurationA(d);
        } catch (e) {}
      }
      if (playerBRef.current && typeof playerBRef.current.getCurrentTime === "function" && playerBReady) {
        try {
          const t = playerBRef.current.getCurrentTime();
          const d = playerBRef.current.getDuration() || 180;
          setTimeB(t);
          setDurationB(d);
        } catch (e) {}
      }
    }, 250);
    return () => clearInterval(timer);
  }, [playerAReady, playerBReady]);

  // Auto-generate cue points and structure elements when a track is loaded to Deck A
  useEffect(() => {
    if (deckA.track) {
      // Generate standard cue slots
      const d = durationA || 180;
      setDeckACues([
        Math.round(d * 0.05), // Cue 1: Intro (5%)
        Math.round(d * 0.20), // Cue 2: Verse / Build (20%)
        Math.round(d * 0.45), // Cue 3: Heavy Drop (45%)
        Math.round(d * 0.80), // Cue 4: Outro (80%)
      ]);
      // Set the Loop Maker track dropdown to this track by default
      setSelectedLoopTrackId(deckA.track.id);
    } else {
      setDeckACues([null, null, null, null]);
    }
  }, [deckA.track, durationA]);

  // Auto-generate cue points and structure elements when a track is loaded to Deck B
  useEffect(() => {
    if (deckB.track) {
      // Generate standard cue slots
      const d = durationB || 180;
      setDeckBCues([
        Math.round(d * 0.05), // Cue 1: Intro (5%)
        Math.round(d * 0.22), // Cue 2: Verse / Build (22%)
        Math.round(d * 0.48), // Cue 3: Heavy Drop (48%)
        Math.round(d * 0.85), // Cue 4: Outro (85%)
      ]);
      if (!selectedLoopTrackId && deckB.track) {
        setSelectedLoopTrackId(deckB.track.id);
      }
    } else {
      setDeckBCues([null, null, null, null]);
    }
  }, [deckB.track, durationB]);

  // --- COMPLETE APPLICATION CONTROL API HUB ---
  useEffect(() => {
    (window as any).proMixingConsoleApi = {
      // 1. Core State Retrieval
      getConsoleState: () => ({
        isPlaying,
        audioVolume,
        alignmentScore,
        deckA: {
          track: deckA.track,
          baseBpm: deckA.baseBpm,
          bpm: deckA.bpm,
          pitch: deckA.pitch,
          phaseOffset: deckA.phaseOffset,
          isMuted: deckA.isMuted,
          autoStretch: deckA.autoStretch,
          transposeOffset: deckA.transposeOffset,
          currentTime: timeA,
          duration: durationA,
          cues: deckACues,
        },
        deckB: {
          track: deckB.track,
          baseBpm: deckB.baseBpm,
          bpm: deckB.bpm,
          pitch: deckB.pitch,
          phaseOffset: deckB.phaseOffset,
          isMuted: deckB.isMuted,
          autoStretch: deckB.autoStretch,
          transposeOffset: deckB.transposeOffset,
          currentTime: timeB,
          duration: durationB,
          cues: deckBCues,
        },
        sampler: {
          activePreset: selectedKaossPadId,
          fxType: kaossFxType,
          pads: samplerPads.map(p => ({
            id: p.id,
            name: p.name,
            isAssigned: p.isAssigned,
            synthType: p.synthType,
            isLoop: !!p.isLoop,
          })),
        },
        automix: {
          isAutoMixing,
          currentIndex: autoMixCurrentIndex,
          playlist: autoMixPlaylist,
          timeRemaining: autoMixTimeRemaining,
          stage: autoMixStage,
          crossfader: autoMixCrossfader,
        }
      }),

      // 2. Transport & Playback
      play: () => {
        initAudioContext();
        setIsPlaying(true);
        setFeedbackMsg("API Action: Play initiated.");
        addApiLog("API Command: Play mixing roll.");
      },
      pause: () => {
        setIsPlaying(false);
        setFeedbackMsg("API Action: Pause initiated.");
        addApiLog("API Command: Pause mixing roll.");
      },
      togglePlay: () => {
        initAudioContext();
        setIsPlaying(prev => {
          addApiLog(`API Command: Toggle Playback (${!prev ? "PLAY" : "PAUSE"}).`);
          return !prev;
        });
      },
      resetEngine: () => {
        handleReset();
        setFeedbackMsg("API Action: Engine Reset.");
        addApiLog("API Command: Full Engine Reset executed.");
      },

      // 3. Deck Parameter Tuning
      loadTrack: (deck: "A" | "B", trackId: string) => {
        const found = tracks.find(t => t.id === trackId);
        if (found) {
          loadTrackToDeck(found, deck);
          setFeedbackMsg(`API Action: Loaded track "${found.title}" to Deck ${deck}.`);
          addApiLog(`API Command: Loaded "${found.title}" to Deck ${deck}.`);
          return true;
        }
        addApiLog(`API Command: Failed loading track ID "${trackId}" (Not found).`);
        return false;
      },
      setVolume: (vol: number) => {
        const val = Math.max(0, Math.min(1, vol));
        setAudioVolume(val);
        setFeedbackMsg(`API Action: Master volume set to ${Math.round(val * 100)}%.`);
        addApiLog(`API Command: Master volume updated to ${Math.round(val * 100)}%.`);
      },
      setMute: (deck: "A" | "B", isMuted: boolean) => {
        if (deck === "A") {
          setDeckA(prev => ({ ...prev, isMuted }));
        } else {
          setDeckB(prev => ({ ...prev, isMuted }));
        }
        setFeedbackMsg(`API Action: Mute state for Deck ${deck} set to ${isMuted}.`);
        addApiLog(`API Command: Muted Deck ${deck} click state: ${isMuted}.`);
      },
      setPitch: (deck: "A" | "B", percentage: number) => {
        const clamped = Math.max(-8, Math.min(8, percentage));
        if (deck === "A") {
          setDeckA(prev => ({ ...prev, pitch: clamped }));
        } else {
          setDeckB(prev => ({ ...prev, pitch: clamped }));
        }
        setFeedbackMsg(`API Action: Set Deck ${deck} pitch speed fader to ${clamped.toFixed(2)}%.`);
        addApiLog(`API Command: Speed pitch Deck ${deck} changed to ${clamped.toFixed(2)}%.`);
      },
      setKeyLock: (deck: "A" | "B", enabled: boolean) => {
        if (deck === "A") {
          setDeckA(prev => ({ ...prev, autoStretch: enabled }));
        } else {
          setDeckB(prev => ({ ...prev, autoStretch: enabled }));
        }
        setFeedbackMsg(`API Action: Master Tempo (Key Lock) for Deck ${deck} set to ${enabled}.`);
        addApiLog(`API Command: Key Lock on Deck ${deck} state: ${enabled}.`);
      },
      setTranspose: (deck: "A" | "B", semitones: number) => {
        const clamped = Math.max(-12, Math.min(12, semitones));
        if (deck === "A") {
          setDeckA(prev => ({ ...prev, transposeOffset: clamped }));
        } else {
          setDeckB(prev => ({ ...prev, transposeOffset: clamped }));
        }
        setFeedbackMsg(`API Action: Digital transpose for Deck ${deck} set to ${clamped > 0 ? "+" : ""}${clamped} semitones.`);
        addApiLog(`API Command: Digital pitch transpose Deck ${deck} set to ${clamped > 0 ? "+" : ""}${clamped} semitones.`);
      },
      syncBeats: () => {
        handleAutoSync();
        setFeedbackMsg("API Action: Full phase and BPM alignment sync executed.");
        addApiLog("API Command: Sync Beats (Phase alignment) completed.");
      },

      // 4. Cue Point Control
      setCuePoint: (deck: "A" | "B", index: number, seconds: number) => {
        const idx = index - 1;
        if (idx >= 0 && idx < 4) {
          if (deck === "A") {
            setDeckACues(prev => prev.map((v, i) => i === idx ? seconds : v));
          } else {
            setDeckBCues(prev => prev.map((v, i) => i === idx ? seconds : v));
          }
          addApiLog(`API Command: Set Cue ${index} on Deck ${deck} to ${seconds}s.`);
          return true;
        }
        return false;
      },
      triggerCue: (deck: "A" | "B", index: number) => {
        const idx = index - 1;
        const cues = deck === "A" ? deckACues : deckBCues;
        if (idx >= 0 && idx < 4 && cues[idx] !== null) {
          handleSeek(deck, cues[idx] as number);
          addApiLog(`API Command: Jumped to Cue ${index} on Deck ${deck} (${cues[idx]}s).`);
          return true;
        }
        addApiLog(`API Command: Failed Cue jump (Cue ${index} on Deck ${deck} is unset).`);
        return false;
      },
      clearCuePoint: (deck: "A" | "B", index: number) => {
        const idx = index - 1;
        if (idx >= 0 && idx < 4) {
          if (deck === "A") {
            setDeckACues(prev => prev.map((v, i) => i === idx ? null : v));
          } else {
            setDeckBCues(prev => prev.map((v, i) => i === idx ? null : v));
          }
          addApiLog(`API Command: Cleared Cue ${index} on Deck ${deck}.`);
          return true;
        }
        return false;
      },

      // 5. Sampler Controls
      triggerPad: (padId: number) => {
        const pad = samplerPads.find(p => p.id === padId);
        if (pad) {
          playSamplerSound(pad);
          addApiLog(`API Command: Triggered pad ${padId} (${pad.name}).`);
          return true;
        }
        return false;
      },
      autoGrabAtmospheres: () => {
        triggerAutoGrab();
        addApiLog("API Command: Auto grabbed Atmosphere stems.");
      },
      sampleLoopFromActiveTrack: (trackId: string, padId: number, beats: number) => {
        const tr = tracks.find(t => t.id === trackId);
        if (tr) {
          sampleLoopFromTrack(tr, padId, beats);
          addApiLog(`API Command: Sample loop initiated for track "${tr.title}" on Pad ${padId}.`);
          return true;
        }
        return false;
      },

      // 6. Automation Sequences
      startAutoMix: () => {
        startAutomatchicMix();
        addApiLog("API Command: Launched Automatchic Mix Roll.");
      },
      stopAutoMix: () => {
        stopAutomatchicMix();
        addApiLog("API Command: Paused Automatchic Mix Roll.");
      }
    };

    return () => {
      delete (window as any).proMixingConsoleApi;
    };
  }, [
    isPlaying, audioVolume, alignmentScore, deckA, deckB, timeA, timeB,
    durationA, durationB, deckACues, deckBCues, selectedKaossPadId, kaossFxType,
    samplerPads, isAutoMixing, autoMixCurrentIndex, autoMixPlaylist,
    autoMixTimeRemaining, autoMixStage, autoMixCrossfader, tracks
  ]);

  // Keep BPM state updated when base BPM or Pitch changes
  useEffect(() => {
    setDeckA(prev => ({
      ...prev,
      bpm: Number((prev.baseBpm * (1 + prev.pitch / 100)).toFixed(2))
    }));
  }, [deckA.baseBpm, deckA.pitch]);

  useEffect(() => {
    setDeckB(prev => ({
      ...prev,
      bpm: Number((prev.baseBpm * (1 + prev.pitch / 100)).toFixed(2))
    }));
  }, [deckB.baseBpm, deckB.pitch]);

  // Main animation frame loop
  useEffect(() => {
    const tick = (time: number) => {
      if (!lastTimeRef.current) lastTimeRef.current = time;
      const deltaTime = (time - lastTimeRef.current) / 1000; // in seconds
      lastTimeRef.current = time;

      if (isPlaying) {
        // Position advances proportional to active BPM
        // 1 beat = 1.0 visual block unit
        const beatsPerSecondA = deckA.bpm / 60;
        const beatsPerSecondB = deckB.bpm / 60;

        let hasYtA = false;
        let hasYtB = false;

        // Sync visual position directly with ready YouTube Player elapsed playback time
        if (playerAReady && playerARef.current && typeof playerARef.current.getCurrentTime === "function") {
          try {
            const ytTimeA = playerARef.current.getCurrentTime();
            if (ytTimeA > 0) {
              positionRefA.current = ytTimeA * beatsPerSecondA;
              hasYtA = true;
            }
          } catch (e) {}
        }

        if (playerBReady && playerBRef.current && typeof playerBRef.current.getCurrentTime === "function") {
          try {
            const ytTimeB = playerBRef.current.getCurrentTime();
            if (ytTimeB > 0) {
              positionRefB.current = ytTimeB * beatsPerSecondB;
              hasYtB = true;
            }
          } catch (e) {}
        }

        // Procedural accumulation if player is not loaded/playing
        if (!hasYtA) {
          positionRefA.current += deltaTime * beatsPerSecondA;
        }
        if (!hasYtB) {
          positionRefB.current += deltaTime * beatsPerSecondB;
        }

        // Discrete beat index crossing triggers
        const currentBeatA = Math.floor(positionRefA.current);
        if (currentBeatA > lastBeatRefA.current) {
          lastBeatRefA.current = currentBeatA;
          if (!deckA.isMuted) {
            const isDownbeat = currentBeatA % 4 === 0;
            playClickSound(isDownbeat ? 600 : 440, isDownbeat);
          }
        } else if (currentBeatA < lastBeatRefA.current - 1) {
          // Reset if they seeked back significantly
          lastBeatRefA.current = currentBeatA;
        }

        const relativePosB = positionRefB.current + deckB.phaseOffset;
        const currentBeatB = Math.floor(relativePosB);
        if (currentBeatB > lastBeatRefB.current) {
          lastBeatRefB.current = currentBeatB;
          if (!deckB.isMuted) {
            const isDownbeat = currentBeatB % 4 === 0;
            playClickSound(isDownbeat ? 880 : 580, isDownbeat);
          }
        } else if (currentBeatB < lastBeatRefB.current - 1) {
          lastBeatRefB.current = currentBeatB;
        }
      }

      // Draw grids
      drawGrid(canvasARef.current, positionRefA.current, 0, "#06b6d4", "DECK A");
      drawGrid(canvasBRef.current, positionRefB.current, deckB.phaseOffset, "#f59e0b", "DECK B");

      // Calculate real-time alignment score
      // Alignment depends on:
      // 1. BPM equivalence (within 0.05 BPM)
      // 2. Phase offset congruence (the fraction of the grids that overlap)
      const bpmDiff = Math.abs(deckA.bpm - deckB.bpm);
      const bpmScore = Math.max(0, 100 - bpmDiff * 30);

      // Phase is aligned if fractional positions match:
      const phaseA = positionRefA.current % 1.0;
      const phaseB = (positionRefB.current + deckB.phaseOffset) % 1.0;
      
      let phaseDiff = Math.abs(phaseA - phaseB);
      if (phaseDiff > 0.5) phaseDiff = 1.0 - phaseDiff; // Wrap around circle
      const phaseScore = Math.max(0, 100 - phaseDiff * 200);

      const score = Math.round(bpmScore * 0.4 + phaseScore * 0.6);
      setAlignmentScore(score);

      animationFrameRef.current = requestAnimationFrame(tick);
    };

    animationFrameRef.current = requestAnimationFrame(tick);
    return () => {
      if (animationFrameRef.current) cancelAnimationFrame(animationFrameRef.current);
    };
  }, [isPlaying, deckA.bpm, deckB.bpm, deckB.phaseOffset, deckA.isMuted, deckB.isMuted, audioVolume]);

  // Visual grid rendering on <canvas>
  const drawGrid = (
    canvas: HTMLCanvasElement | null,
    scrollPos: number,
    phaseOffset: number,
    accentColor: string,
    deckLabel: string
  ) => {
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const width = canvas.width;
    const height = canvas.height;

    // Reset canvas with sophisticated dark neutral background
    ctx.fillStyle = "#18181b"; // zinc-900
    ctx.fillRect(0, 0, width, height);

    // Draw horizontal timeline guideline
    ctx.strokeStyle = "#3f3f46"; // zinc-700
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, height / 2);
    ctx.lineTo(width, height / 2);
    ctx.stroke();

    // Visual spacing per beat (e.g. 150px per beat)
    const beatSpacing = 160;
    const centerPlayhead = width / 2;

    // Draw grid lines
    // Draw 6 beats to the left and right of screen
    const range = 6;
    const currentFloatBeat = scrollPos + phaseOffset;

    for (let i = Math.floor(currentFloatBeat) - range; i <= Math.ceil(currentFloatBeat) + range; i++) {
      // Offset from current beat
      const offsetBeats = i - currentFloatBeat;
      // Pixel position
      const x = centerPlayhead + offsetBeats * beatSpacing;

      if (x < 0 || x > width) continue;

      const isDownbeat = i % 4 === 0;

      // Draw beat vertical marker line
      ctx.strokeStyle = isDownbeat ? accentColor : "#52525b"; // zinc-600
      ctx.lineWidth = isDownbeat ? 3 : 1.5;

      ctx.beginPath();
      ctx.moveTo(x, 15);
      ctx.lineTo(x, height - 15);
      ctx.stroke();

      // Simulated wave details between beatgrids for premium feel
      ctx.fillStyle = isDownbeat ? `${accentColor}22` : "#27272a";
      ctx.fillRect(x - 5, height / 2 - 25, 10, 50);

      // Draw secondary simulated waves (mini-peaks) to look like a real audio wave
      ctx.fillStyle = isDownbeat ? `${accentColor}44` : "#3f3f46";
      ctx.fillRect(x + beatSpacing / 2 - 2, height / 2 - 15, 4, 30);
      ctx.fillRect(x + beatSpacing / 4 - 1, height / 2 - 8, 2, 16);
      ctx.fillRect(x + (3 * beatSpacing) / 4 - 1, height / 2 - 8, 2, 16);

      // Label the beat index for grid alignment feedback
      ctx.fillStyle = isDownbeat ? accentColor : "#a1a1aa";
      ctx.font = "bold 11px system-ui";
      ctx.fillText(isDownbeat ? `BEAT ${((i % 16) + 16) % 16 || 16}` : `.`, x - 18, height - 2);
    }

    // Draw central Playhead indicator (The physical needle/laser point)
    ctx.strokeStyle = "#ef4444"; // pure vibrant red
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.moveTo(centerPlayhead, 0);
    ctx.lineTo(centerPlayhead, height);
    ctx.stroke();

    // Playhead glowing node
    ctx.fillStyle = "#ef4444";
    ctx.beginPath();
    ctx.arc(centerPlayhead, height / 2, 6, 0, Math.PI * 2);
    ctx.fill();

    // Beat flash indicator: triggers visual glow on center playhead crossing
    const currentFractionalBeat = currentFloatBeat % 1.0;
    const isVeryCloseToBeat = currentFractionalBeat < 0.08 || currentFractionalBeat > 0.92;
    if (isVeryCloseToBeat) {
      ctx.shadowColor = accentColor;
      ctx.shadowBlur = 15;
      ctx.fillStyle = `${accentColor}88`;
      ctx.beginPath();
      ctx.arc(centerPlayhead, height / 2, 16, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0; // reset
    }

    // Draw Deck Label
    ctx.fillStyle = "#ffffff";
    ctx.font = "bold 12px monospace";
    ctx.fillText(deckLabel, 15, 25);
  };

  // Perform full hardware-style BPM & Phase Sync
  const handleAutoSync = () => {
    initAudioContext();
    // Instantly lock Deck B BPM to Deck A BPM by calculating correct pitch percentage
    const targetBpm = deckA.bpm;
    const pitchRequired = ((targetBpm / deckB.baseBpm) - 1) * 100;

    // Shift Phase of B to match A perfectly (0 offset)
    setDeckB(prev => ({
      ...prev,
      pitch: Number(pitchRequired.toFixed(4)),
      bpm: targetBpm,
      phaseOffset: 0
    }));

    // Align accumulator visual positions
    positionRefB.current = positionRefA.current;
  };

  const handleReset = () => {
    setIsPlaying(false);
    positionRefA.current = 0;
    positionRefB.current = 0;
    setDeckA(prev => ({ ...prev, pitch: 0, phaseOffset: 0 }));
    setDeckB(prev => ({ ...prev, pitch: 0, phaseOffset: 0.15 }));
  };

  // Nudge temporary pitch bending (+/- phase shift)
  const nudgeDeckB = (direction: "forward" | "backward") => {
    initAudioContext();
    const shift = direction === "forward" ? 0.02 : -0.02;
    setDeckB(prev => ({
      ...prev,
      phaseOffset: Number((prev.phaseOffset + shift).toFixed(4))
    }));
  };

  // Load selected tracks into active decks
  const loadTrackToDeck = (track: Track, deck: "A" | "B") => {
    initAudioContext();
    const initialBpm = track.bpm;
    if (deck === "A") {
      setDeckA(prev => ({
        ...prev,
        track,
        baseBpm: initialBpm,
        bpm: initialBpm * (1 + prev.pitch / 100)
      }));
    } else {
      setDeckB(prev => ({
        ...prev,
        track,
        baseBpm: initialBpm,
        bpm: initialBpm * (1 + prev.pitch / 100)
      }));
    }
  };

  const handleShareSession = () => {
    const params = new URLSearchParams();
    if (deckA.track) params.set("deckATrackId", deckA.track.id);
    if (deckB.track) params.set("deckBTrackId", deckB.track.id);
    params.set("cuesA", JSON.stringify(deckACues));
    params.set("cuesB", JSON.stringify(deckBCues));
    if (selectedLoopTrackId) params.set("loopTrackId", selectedLoopTrackId);
    params.set("loopPadId", selectedLoopPadId.toString());
    params.set("loopBeats", selectedLoopBeats.toString());

    const shareUrl = `${window.location.origin}${window.location.pathname}?${params.toString()}`;
    setShareLink(shareUrl);
    setShowShareBanner(true);

    navigator.clipboard.writeText(shareUrl)
      .then(() => {
        setFeedbackMsg("🔗 Shareable training session link copied to clipboard!");
      })
      .catch((err) => {
        console.error("Clipboard write failed: ", err);
        setFeedbackMsg("Session link prepared. Please copy it below.");
      });
  };

  // Dynamic key display helper
  const getDisplayKey = (deck: DeckState) => {
    if (!deck.track) return "No Key";
    
    // Calculate pitch semitone shifting only if Auto Stretch (Key Lock) is disabled (OFF)
    let pitchSemitones = 0;
    if (!deck.autoStretch) {
      pitchSemitones = Math.round(Math.log2(1 + deck.pitch / 100) * 12);
    }
    
    const finalShift = pitchSemitones + deck.transposeOffset;
    if (finalShift === 0) {
      return deck.track.camelotKey;
    }
    
    const transposed = transposeCamelotKey(deck.track.camelotKey, finalShift);
    return `${transposed.camelot} (${finalShift > 0 ? "+" : ""}${finalShift} st)`;
  };

  const handleKeySyncB = () => {
    if (!deckA.track || !deckB.track) return;
    initAudioContext();
    const keyA = getDisplayKey(deckA).split(" ")[0]; // get current active key of Deck A
    const keyB = deckB.track.camelotKey; // base key B

    const shift = getShortestSemitoneShift(keyA, keyB);
    
    setDeckB(prev => ({
      ...prev,
      transposeOffset: shift
    }));

    setFeedbackMsg(`Key Sync Active: Transposed Deck B by ${shift > 0 ? "+" : ""}${shift} semitones to perfectly match Deck A's key (${keyA})!`);
  };

  const handleResetKeyA = () => {
    setDeckA(prev => ({ ...prev, transposeOffset: 0 }));
  };

  const handleResetKeyB = () => {
    setDeckB(prev => ({ ...prev, transposeOffset: 0 }));
  };

  const handleShuffleCrate = () => {
    initAudioContext();
    if (!tracks || tracks.length < 2) {
      setFeedbackMsg("The current library needs at least 2 tracks to run an intelligent shuffle.");
      return;
    }

    // Generate all unique unordered pairs
    const pairs: { trackA: Track; trackB: Track; score: number }[] = [];
    for (let i = 0; i < tracks.length; i++) {
      for (let j = i + 1; j < tracks.length; j++) {
        const trackA = tracks[i];
        const trackB = tracks[j];
        const comparison = compareTracks(trackA, trackB);
        pairs.push({
          trackA,
          trackB,
          score: comparison.overallScore
        });
      }
    }

    if (pairs.length === 0) return;

    // Find maximum compatibility score in the library
    const maxScore = Math.max(...pairs.map(p => p.score));

    // Get all pairs that are highly compatible (within 15 points of the best possible match, and at least score 70)
    const threshold = Math.max(70, maxScore - 15);
    let compatiblePairs = pairs.filter(p => p.score >= threshold);
    
    // Fallback if none meet the high compatibility criteria
    if (compatiblePairs.length === 0) {
      compatiblePairs = pairs;
    }

    // Select one pair randomly from the compatible options to ensure the shuffle offers variety on subsequent clicks
    const randomPair = compatiblePairs[Math.floor(Math.random() * compatiblePairs.length)];

    // Randomly assign to Deck A and Deck B to keep it balanced and dynamic
    const shouldSwap = Math.random() > 0.5;
    const finalA = shouldSwap ? randomPair.trackB : randomPair.trackA;
    const finalB = shouldSwap ? randomPair.trackA : randomPair.trackB;

    // Load to decks and reset tempo/transpose offsets for a clean, stable starting point
    setDeckA(prev => ({
      ...prev,
      track: finalA,
      baseBpm: finalA.bpm,
      bpm: finalA.bpm,
      pitch: 0,
      phaseOffset: 0,
      transposeOffset: 0,
    }));

    setDeckB(prev => ({
      ...prev,
      track: finalB,
      baseBpm: finalB.bpm,
      bpm: finalB.bpm,
      pitch: 0,
      phaseOffset: 0.15, // standard minor training offset
      transposeOffset: 0,
    }));

    // Calculate details for feedback
    const details = compareTracks(finalA, finalB);
    setFeedbackMsg(`Shuffle Crate matched: "${finalA.title}" on Deck A & "${finalB.title}" on Deck B (Harmonic Score: ${details.overallScore}%) - ${details.keyAdvice}!`);
  };

  // --- AUTOMATCHIC MIX FUNCTIONS ---
  const startAutomatchicMix = () => {
    initAudioContext();
    if (!tracks || tracks.length < 2) {
      setFeedbackMsg("The current library needs at least 2 tracks to start the Automatchic Mix.");
      return;
    }

    // 1. Generate highly harmonic compatible playlist dynamically using a greedy sequence
    const playlist: Track[] = [];
    const usedIds = new Set<string>();

    // Start with the track currently on Deck A, or the first track in the library
    let current = deckA.track || tracks[0];
    playlist.push(current);
    usedIds.add(current.id);

    while (playlist.length < Math.min(6, tracks.length)) {
      let bestNext: Track | null = null;
      let highestScore = -1;

      for (const track of tracks) {
        if (!usedIds.has(track.id)) {
          const comp = compareTracks(current, track);
          if (comp.overallScore > highestScore) {
            highestScore = comp.overallScore;
            bestNext = track;
          }
        }
      }

      if (bestNext) {
        playlist.push(bestNext);
        usedIds.add(bestNext.id);
        current = bestNext;
      } else {
        break;
      }
    }

    setAutoMixPlaylist(playlist);
    setAutoMixCurrentIndex(0);
    setIsAutoMixing(true);
    setIsPlaying(true); // make sure engines are running
    setAutoMixTimeRemaining(autoMixDuration);
    setAutoMixStage("ready");
    setAutoMixCrossfader(-100); // start fully on Deck A

    // Load first track to Deck A and unmute it
    setDeckA(prev => ({
      ...prev,
      track: playlist[0],
      baseBpm: playlist[0].bpm,
      bpm: playlist[0].bpm,
      pitch: 0,
      phaseOffset: 0,
      transposeOffset: 0,
      isMuted: false, // Ensure active deck is unmuted for visual beats
    }));

    // Load second track to Deck B and unmute it (crossfader handles actual volume)
    if (playlist[1]) {
      setDeckB(prev => ({
        ...prev,
        track: playlist[1],
        baseBpm: playlist[1].bpm,
        bpm: playlist[1].bpm,
        pitch: 0,
        phaseOffset: 0.15, // standard minor training offset
        transposeOffset: 0,
        isMuted: false, // Ensure B visual grids also flash together during mix
      }));
    }

    setFeedbackMsg("⚡ Automatchic Mix Initialized: Curated a pro-grade harmonic playlist!");
    setAutoMixStatus("Loaded primary track on Deck A, queuing Deck B.");
  };

  const stopAutomatchicMix = () => {
    setIsAutoMixing(false);
    setAutoMixStage("ready");
    setAutoMixCrossfader(-100);
    setIsPlaying(false);
    setFeedbackMsg("Automatchic Mix Session paused.");
  };

  const skipToNextAutoMixTransition = () => {
    if (!isAutoMixing) return;
    setAutoMixTimeRemaining(Math.min(autoMixTimeRemaining, 5));
    setFeedbackMsg("Skipping forward to the active transition window...");
  };

  // --- AUTOMATCHIC MIX MAIN LOOP ---
  useEffect(() => {
    if (!isAutoMixing) return;

    const interval = setInterval(() => {
      setAutoMixTimeRemaining(prev => {
        const nextTime = prev - 1;

        if (nextTime <= 0) {
          // Transition completes!
          setAutoMixStage("complete");
          setAutoMixStatus("Transition complete! Loading next tracks in sequence...");

          // Advance playlist index
          setAutoMixCurrentIndex(currIdx => {
            const nextIdx = (currIdx + 1) % autoMixPlaylist.length;
            const nextTrackA = autoMixPlaylist[nextIdx];
            const nextTrackB = autoMixPlaylist[(nextIdx + 1) % autoMixPlaylist.length];

            // Perform final swap of decks
            setDeckA(prevA => ({
              ...prevA,
              track: nextTrackA,
              baseBpm: nextTrackA.bpm,
              bpm: nextTrackA.bpm,
              pitch: 0,
              phaseOffset: 0,
              transposeOffset: 0,
              isMuted: false,
            }));

            if (nextTrackB) {
              setDeckB(prevB => ({
                ...prevB,
                track: nextTrackB,
                baseBpm: nextTrackB.bpm,
                bpm: nextTrackB.bpm,
                pitch: 0,
                phaseOffset: 0.15, // Reset for next sync
                transposeOffset: 0,
                isMuted: false,
              }));
            }

            return nextIdx;
          });

          // Reset crossfader to fully Deck A
          setAutoMixCrossfader(-100);
          setAutoMixStage("ready");
          setAutoMixStatus("Playing main track, queuing next deck.");

          return autoMixDuration;
        }

        // Action sequence as we count down
        if (nextTime === Math.round(autoMixDuration * 0.8)) {
          // ⚡ T-minus 80%: Auto Beatgrid & Phase Sync
          setAutoMixStage("sync");
          setAutoMixStatus("Auto Sync Beats: Aligning BPM and Phase matching...");
          handleAutoSync();
        } 
        else if (nextTime === Math.round(autoMixDuration * 0.65)) {
          // 🔑 T-minus 65%: Harmonic Pitch Transpose
          setAutoMixStage("keysync");
          setAutoMixStatus("Harmonic Key Sync: Transposing and tuning Decks...");
          handleKeySyncB();
        } 
        else if (nextTime === Math.round(autoMixDuration * 0.5)) {
          // 🧬 T-minus 50%: AI Atmosphere Sample Grab
          setAutoMixStage("grab");
          setAutoMixStatus("AI Atmosphere Grab: Extracting genre stems into Sampler...");
          triggerAutoGrab();
        } 
        else if (nextTime === Math.round(autoMixDuration * 0.35)) {
          // 🥁 T-minus 35%: Rhythmic FX Triggering
          setAutoMixStage("fx");
          setAutoMixStatus("Sampler FX Injection: Inserting drum and riser sweeps...");
          // Trigger a random assigned sampler pad
          const assignedPads = samplerPads.filter(p => p.isAssigned);
          if (assignedPads.length > 0) {
            const randomPad = assignedPads[Math.floor(Math.random() * assignedPads.length)];
            playSamplerSound(randomPad);
          }
        } 
        else if (nextTime <= 5) {
          // 🎚️ Last 5 seconds: Smooth Active Crossfade & Energy Mod
          setAutoMixStage("fade");
          setAutoMixStatus("Crossfading channels... Blending atmospheres...");
          
          // Linearly transition crossfader from -100 to 100
          const fadeProgress = (5 - nextTime) / 5; // 0 to 1
          const nextCrossfaderVal = -100 + (fadeProgress * 200);
          setAutoMixCrossfader(Math.round(nextCrossfaderVal));

          // Trigger sampler beat elements to lock in transition
          if (nextTime === 3 || nextTime === 1) {
            const kickPad = samplerPads.find(p => p.synthType === "kick" && p.isAssigned);
            if (kickPad) playSamplerSound(kickPad);
          }
        }

        return nextTime;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [isAutoMixing, autoMixPlaylist, autoMixDuration, samplerPads]);

  return (
    <div id="beatgrid-tool-panel" className="bg-zinc-950 border border-zinc-850 rounded-xl p-5 shadow-2xl space-y-5 animate-fade-in">
      {/* Header and Sync Display */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 pb-4 border-b border-zinc-850">
        <div>
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full bg-cyan-500 animate-pulse"></span>
            <h3 className="text-base font-bold text-zinc-100 uppercase tracking-wider font-sans">Pro Mixing Workspace Console</h3>
          </div>
          <p className="text-[11px] text-zinc-400">Lock scrolling tempos & phase sync, trigger custom sampler pads, and shape mixing energy interactively.</p>
        </div>
        
        <div className="flex items-center gap-3 w-full md:w-auto">
          {/* Global Alignment HUD */}
          <div className="flex-1 md:flex-initial bg-zinc-900 border border-zinc-850 rounded-lg px-4 py-1.5 text-center min-w-[150px]">
            <div className="text-[9px] uppercase tracking-widest text-zinc-500 font-bold">GRID SYNC ACCURACY</div>
            <div className={`text-base font-black font-mono tracking-tight ${
              alignmentScore >= 95 ? "text-emerald-400" : alignmentScore >= 80 ? "text-amber-400" : "text-rose-400"
            }`}>
              {alignmentScore}%
            </div>
            <div className="w-full bg-zinc-850 h-1 rounded-full mt-1 overflow-hidden">
              <div 
                className={`h-full transition-all duration-150 ${
                  alignmentScore >= 95 ? "bg-emerald-500" : alignmentScore >= 80 ? "bg-amber-500" : "bg-rose-500"
                }`}
                style={{ width: `${alignmentScore}%` }}
              ></div>
            </div>
          </div>

          <button
            onClick={handleShareSession}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 hover:border-zinc-700 text-zinc-300 hover:text-white rounded-lg text-3xs font-bold uppercase tracking-wider transition-all cursor-pointer whitespace-nowrap"
            title="Export and Share Training Session Link"
            id="share-session-btn"
          >
            <Link2 size={13} className="text-emerald-400" />
            <span>Share Session</span>
          </button>

          <button
            onClick={() => setShowHelp(!showHelp)}
            className="p-1.5 border border-zinc-800 rounded-lg text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900 transition-all cursor-pointer"
            title="Help Guidelines"
            id="help-btn"
          >
            <HelpCircle size={15} />
          </button>
        </div>
      </div>

      {/* Shareable Link Banner */}
      {showShareBanner && shareLink && (
        <div className="bg-zinc-900 border border-emerald-900/60 p-3.5 rounded-xl flex items-center justify-between gap-4 animate-fade-in" id="share-session-banner">
          <div className="flex-1 min-w-0 space-y-1">
            <h4 className="text-[9px] font-black text-emerald-400 uppercase tracking-wider flex items-center gap-1.5">
              <span>Shareable Session Link Ready</span>
              <span className="bg-emerald-950 text-emerald-400 text-[8px] font-mono px-1 rounded border border-emerald-900">COPIED</span>
            </h4>
            <div className="flex items-center gap-2 bg-zinc-950 border border-zinc-850 rounded px-2 py-1">
              <input
                type="text"
                readOnly
                value={shareLink}
                className="bg-transparent border-none text-[10px] font-mono text-zinc-400 w-full focus:outline-none select-all"
                onClick={(e) => (e.target as HTMLInputElement).select()}
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                navigator.clipboard.writeText(shareLink);
                setFeedbackMsg("🔗 Shared link copied to clipboard!");
              }}
              className="bg-zinc-800 hover:bg-zinc-700 text-zinc-300 hover:text-white border border-zinc-700 text-3xs font-bold uppercase tracking-wider px-3 py-2 rounded-lg transition-all cursor-pointer whitespace-nowrap"
            >
              Copy Link
            </button>
            <button
              onClick={() => setShowShareBanner(false)}
              className="text-zinc-500 hover:text-zinc-350 text-xs font-bold px-2 py-1 hover:bg-zinc-850 rounded transition-colors cursor-pointer"
            >
              ✕
            </button>
          </div>
        </div>
      )}

      {/* Embedded Help Overlay */}
      {showHelp && (
        <div className="bg-zinc-900/90 border border-zinc-800 p-4 rounded-lg text-3xs leading-relaxed text-zinc-300 space-y-2 animate-fade-in">
          <p className="font-bold text-zinc-100">Performance Console Features Guide:</p>
          <ul className="list-disc pl-4 space-y-1">
            <li><strong>Auto Stretch (Key Lock / Master Tempo):</strong> Keeps pitch locked at the original key even as you adjust tempo (BPM). If disabled, changing speed causes natural vinyl pitch-bending (shifts Camelot key!).</li>
            <li><strong>Auto Pitch (Key Sync):</strong> Instantly transposes Deck B's pitch to perfectly align with the current key of Deck A, ensuring mathematically seamless harmonic blending.</li>
            <li><strong>Performance Sampler:</strong> Trigger procedurally synthesized beat elements, or record custom vocal bits using your mic (falls back to clean console loop capture). Click "Auto Grab" to fill unused slots with tailored loops!</li>
            <li><strong>Interactive Energy Graph:</strong> Visualizes energy based on speed. Reshape the curve using single-finger drags to plot transitions, or pinch-to-stretch/scale with 2-finger touch/Shift+drags.</li>
          </ul>
        </div>
      )}

      {/* 🤝 MULTI-DEVICE HARDWARE SYNCHRONIZATION BAR */}
      <div className="bg-zinc-900 border border-zinc-850 p-3.5 rounded-xl flex flex-col md:flex-row justify-between items-stretch md:items-center gap-4 shadow-lg shadow-black/30">
        <div className="flex items-center gap-3">
          <div className="relative flex items-center justify-center">
            <span className={`w-3 h-3 rounded-full ${roomStateSynced ? "bg-emerald-500 shadow-[0_0_10px_rgba(16,185,129,0.6)] animate-pulse" : "bg-zinc-600"}`}></span>
          </div>
          <div>
            <h4 className="text-xs font-bold text-zinc-100 uppercase tracking-wider flex items-center gap-1.5">
              <span>Tactile Device Link Core</span>
              {roomStateSynced && <span className="bg-emerald-950 text-emerald-400 text-[8px] font-mono px-1 rounded border border-emerald-900">CONNECTED</span>}
            </h4>
            <p className="text-4xs text-zinc-400">
              {roomStateSynced 
                ? `Sync group [${roomCode.toUpperCase()}] active. ${connectedClients.length} hardware unit(s) online.` 
                : "Synchronize multiple screens as a single unified tactile control desk."}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* Room code input */}
          {!roomStateSynced ? (
            <>
              <input
                type="text"
                placeholder="ROOM CODE..."
                value={roomCode}
                onChange={(e) => setRoomCode(e.target.value.toUpperCase())}
                className="bg-zinc-950 border border-zinc-800 text-xs font-mono font-bold text-center uppercase text-zinc-100 w-[120px] rounded px-2.5 py-1.5 focus:ring-1 focus:ring-cyan-500 focus:outline-none"
              />
              <input
                type="text"
                placeholder="DEVICE NAME..."
                value={deviceName}
                onChange={(e) => setDeviceName(e.target.value)}
                className="bg-zinc-950 border border-zinc-800 text-xs text-zinc-300 w-[140px] rounded px-2.5 py-1.5 focus:ring-1 focus:ring-cyan-500 focus:outline-none"
              />
              <button
                onClick={() => {
                  const code = roomCode || "BEAT";
                  setRoomCode(code);
                  connectToSyncRoom(code);
                }}
                disabled={isWSAconnecting}
                className="bg-cyan-500 hover:bg-cyan-400 text-zinc-950 text-[10px] font-black uppercase tracking-widest px-3.5 py-2 rounded-lg transition-all cursor-pointer shadow-md shadow-cyan-950/20 active:scale-95"
              >
                {isWSAconnecting ? "Linking..." : "Link Device"}
              </button>
            </>
          ) : (
            <>
              {/* Display other devices */}
              {connectedClients.length > 1 && (
                <div className="flex items-center gap-1.5 bg-zinc-950/60 border border-zinc-850 px-2.5 py-1 rounded-md text-3xs font-mono text-zinc-400 max-w-[200px] overflow-hidden truncate" title="Connected network devices">
                  <span className="text-emerald-400">●</span>
                  <span>Units: {connectedClients.map(c => c.name || "Device").join(", ")}</span>
                </div>
              )}

              {/* Layout Role Swapper */}
              <div className="flex items-center gap-1.5 bg-zinc-950 border border-zinc-850 rounded px-1.5 py-1">
                <span className="text-4xs text-zinc-500 font-bold uppercase tracking-wider">Screen Layout:</span>
                <select
                  value={deviceRole}
                  onChange={(e) => {
                    const nextRole = e.target.value as any;
                    setDeviceRole(nextRole);
                    connectToSyncRoom(roomCode, nextRole);
                  }}
                  className="bg-transparent border-none text-[10px] font-extrabold text-cyan-400 font-mono tracking-wide cursor-pointer focus:outline-none"
                >
                  <option value="all">All-In-One Main Console</option>
                  <option value="deckA">Tactile Platter Deck A</option>
                  <option value="deckB">Tactile Platter Deck B</option>
                  <option value="sampler">Performance Kaoss / Sampler</option>
                  <option value="mixer">Hardware Mixer & Automix</option>
                </select>
              </div>

              <button
                onClick={disconnectFromSyncRoom}
                className="border border-zinc-800 hover:border-rose-900/60 hover:bg-rose-950/20 text-zinc-400 hover:text-rose-400 text-4xs font-bold uppercase tracking-widest px-3 py-2 rounded-lg transition-colors cursor-pointer"
              >
                Disconnect
              </button>
            </>
          )}
        </div>
      </div>

      {deviceRole !== "all" ? (
        <div className="space-y-4 pt-1">
          {deviceRole === "deckA" && renderTactileDeckScreen("A")}
          {deviceRole === "deckB" && renderTactileDeckScreen("B")}
          {deviceRole === "sampler" && renderTactileSamplerScreen()}
          {deviceRole === "mixer" && renderTactileMixerScreen()}
        </div>
      ) : (
        <>
          {/* Navigation tabs */}
          <div className="flex border-b border-zinc-850/80 gap-1 text-[11px] pb-1">
        <button
          onClick={() => {
            stopKaossSound();
            setActiveTab("decks");
          }}
          className={`pb-1.5 px-3 font-bold uppercase tracking-wider transition-all border-b-2 cursor-pointer ${
            activeTab === "decks"
              ? "border-cyan-500 text-cyan-400"
              : "border-transparent text-zinc-500 hover:text-zinc-300"
          }`}
        >
          <div className="flex items-center gap-1.5">
            <Disc size={12} className={activeTab === "decks" && isPlaying ? "animate-spin" : ""} />
            Dual Decks Console
          </div>
        </button>
        <button
          onClick={() => {
            stopKaossSound();
            initAudioContext();
            setActiveTab("sampler");
          }}
          className={`pb-1.5 px-3 font-bold uppercase tracking-wider transition-all border-b-2 cursor-pointer ${
            activeTab === "sampler"
              ? "border-purple-500 text-purple-400"
              : "border-transparent text-zinc-500 hover:text-zinc-300"
          }`}
        >
          <div className="flex items-center gap-1.5">
            <Radio size={12} />
            Performance Sampler
            <span className="text-[8px] bg-purple-950/60 border border-purple-900/60 px-1 py-0.5 rounded font-bold text-purple-300 animate-pulse">NEW</span>
          </div>
        </button>
        <button
          onClick={() => {
            stopKaossSound();
            initAudioContext();
            setActiveTab("energy");
          }}
          className={`pb-1.5 px-3 font-bold uppercase tracking-wider transition-all border-b-2 cursor-pointer ${
            activeTab === "energy"
              ? "border-emerald-500 text-emerald-400"
              : "border-transparent text-zinc-500 hover:text-zinc-300"
          }`}
        >
          <div className="flex items-center gap-1.5">
            <Activity size={12} />
            Session Energy Graph
          </div>
        </button>
        <button
          onClick={() => {
            stopKaossSound();
            initAudioContext();
            setActiveTab("automix");
          }}
          className={`pb-1.5 px-3 font-bold uppercase tracking-wider transition-all border-b-2 cursor-pointer ${
            activeTab === "automix"
              ? "border-amber-500 text-amber-400"
              : "border-transparent text-zinc-500 hover:text-zinc-300"
          }`}
        >
          <div className="flex items-center gap-1.5">
            <Sparkles size={12} className={isAutoMixing ? "text-amber-400 animate-spin" : "text-zinc-500"} />
            Automatchic Mix
            <span className="text-[8px] bg-amber-950/60 border border-amber-900/60 px-1 py-0.5 rounded font-bold text-amber-300 animate-pulse">PRO</span>
          </div>
        </button>
        <button
          onClick={() => {
            stopKaossSound();
            initAudioContext();
            setActiveTab("radial");
          }}
          className={`pb-1.5 px-3 font-bold uppercase tracking-wider transition-all border-b-2 cursor-pointer ${
            activeTab === "radial"
              ? "border-emerald-500 text-emerald-400"
              : "border-transparent text-zinc-500 hover:text-zinc-300"
          }`}
          id="tab-radial"
        >
          <div className="flex items-center gap-1.5">
            <Orbit size={12} className={isPlaying ? "animate-spin text-emerald-400" : "text-zinc-500"} />
            Radial Controller
            <span className="text-[8px] bg-emerald-950/60 border border-emerald-900/60 px-1 py-0.5 rounded font-bold text-emerald-300 animate-pulse">GESTURE</span>
          </div>
        </button>
      </div>

      {/* Global Status/Feedback Notification Banner */}
      {feedbackMsg && (
        <div className="flex items-center justify-between gap-2 bg-cyan-950/20 border border-cyan-900/40 px-3.5 py-2.5 rounded-lg text-3xs text-cyan-300 font-mono animate-fade-in">
          <div className="flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse"></span>
            <span>{feedbackMsg}</span>
          </div>
          <button 
            onClick={() => setFeedbackMsg("")}
            className="text-zinc-500 hover:text-zinc-300 transition-colors text-[10px] font-bold px-1.5 cursor-pointer"
            title="Dismiss message"
          >
            ✕
          </button>
        </div>
      )}

      {/* TAB CONTENT: ACTIVE DUAL DECKS CONSOLE */}
      {activeTab === "decks" && (
        <div className="space-y-5 animate-fade-in">
          {/* Action Bar for Intelligent Crate Shuffling */}
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center bg-zinc-900/40 border border-zinc-850/60 p-4 rounded-xl gap-4">
            <div className="space-y-1 max-w-xl">
              <h4 className="text-xs font-bold text-zinc-100 uppercase tracking-wider">Intelligent Mix Launcher</h4>
              <p className="text-3xs text-zinc-400">
                Let the AI analyze your crate. One-click selects two tracks with perfect BPM matching and high harmonic key compatibility.
              </p>
            </div>
            <button
              onClick={handleShuffleCrate}
              className="flex items-center gap-2 py-2 px-4 bg-cyan-500 hover:bg-cyan-400 text-zinc-950 font-bold text-xs uppercase tracking-wider rounded-lg transition-all cursor-pointer shadow-md shadow-cyan-950/20 active:scale-95 whitespace-nowrap self-stretch sm:self-auto justify-center"
              title="Intelligently shuffle and load a compatible track pair"
              id="shuffle-crate-btn"
            >
              <Shuffle size={13} className="text-zinc-950" />
              Shuffle Crate
            </button>
          </div>

          {/* Main Track Loading Row */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Deck A Loader */}
            <div className="bg-zinc-900/50 border border-zinc-850 p-4 rounded-lg flex flex-col justify-between space-y-4">
              <div className="flex justify-between items-start gap-2">
                <div className="space-y-1">
                  <span className="text-[10px] font-bold text-cyan-400 tracking-widest">DECK A</span>
                  <h4 className="text-xs font-bold text-white truncate max-w-[200px]">
                    {deckA.track ? `${deckA.track.title}` : "No Track Loaded"}
                  </h4>
                  <p className="text-[11px] text-zinc-400 truncate max-w-[200px]">
                    {deckA.track ? deckA.track.artist : "Select a track to load"}
                  </p>
                </div>
                {deckA.track && (
                  <div className="flex flex-col items-end gap-1 font-mono">
                    <span className="bg-cyan-950 text-cyan-400 border border-cyan-900 text-[10px] px-2 py-0.5 rounded font-black">
                      {deckA.bpm} BPM | {getDisplayKey(deckA)}
                    </span>
                    {deckA.transposeOffset !== 0 && (
                      <button 
                        onClick={handleResetKeyA}
                        className="text-[8px] text-zinc-500 hover:text-zinc-300 transition-colors underline cursor-pointer"
                        title="Reset manual key transposition"
                      >
                        Reset Key Transpose
                      </button>
                    )}
                  </div>
                )}
              </div>

              {/* Key Pitch Lock & Auto Stretch Controls for Deck A */}
              {deckA.track && (
                <div className="bg-zinc-950/60 p-2.5 rounded border border-zinc-850/80 grid grid-cols-2 gap-2 text-3xs text-zinc-400">
                  <div className="flex items-center gap-1.5">
                    <input
                      type="checkbox"
                      id="deck-a-keylock"
                      checked={deckA.autoStretch}
                      onChange={(e) => setDeckA(prev => ({ ...prev, autoStretch: e.target.checked }))}
                      className="accent-cyan-500 h-3 w-3 rounded border-zinc-800 bg-zinc-950 cursor-pointer"
                    />
                    <label htmlFor="deck-a-keylock" className="cursor-pointer font-bold select-none text-[9px] uppercase tracking-wider">
                      Key Lock (Auto Stretch)
                    </label>
                  </div>
                  <div className="text-right text-zinc-500 font-mono">
                    {deckA.autoStretch ? "🔒 Speed Stretched Only" : "🎸 Pitch Transposed"}
                  </div>
                </div>
              )}
              
              <div className="flex gap-2 items-center">
                <select
                  onChange={(e) => {
                    const tr = tracks.find(t => t.id === e.target.value);
                    if (tr) loadTrackToDeck(tr, "A");
                  }}
                  value={deckA.track?.id || ""}
                  className="bg-zinc-950 border border-zinc-800 rounded px-2 py-1 text-xs text-zinc-300 flex-1 focus:ring-1 focus:ring-cyan-500 focus:outline-none"
                  id="deck-a-select"
                >
                  <option value="" disabled>Load Song to Deck A...</option>
                  {tracks.map(t => (
                    <option key={t.id} value={t.id}>{t.title} ({t.bpm} BPM - {t.camelotKey})</option>
                  ))}
                </select>
                <button
                  onClick={() => {
                    initAudioContext();
                    setDeckA(prev => ({ ...prev, isMuted: !prev.isMuted }));
                  }}
                  className={`p-1.5 border rounded cursor-pointer transition-all ${
                    !deckA.isMuted 
                      ? "bg-cyan-950 border-cyan-800 text-cyan-400" 
                      : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                  }`}
                  title={deckA.isMuted ? "Unmute Beat Click" : "Mute Beat Click"}
                  id="deck-a-mute"
                >
                  {deckA.isMuted ? <VolumeX size={15} /> : <Volume2 size={15} />}
                </button>
              </div>
            </div>

            {/* Deck B Loader */}
            <div className="bg-zinc-900/50 border border-zinc-850 p-4 rounded-lg flex flex-col justify-between space-y-4">
              <div className="flex justify-between items-start gap-2">
                <div className="space-y-1">
                  <span className="text-[10px] font-bold text-amber-500 tracking-widest">DECK B</span>
                  <h4 className="text-xs font-bold text-white truncate max-w-[200px]">
                    {deckB.track ? `${deckB.track.title}` : "No Track Loaded"}
                  </h4>
                  <p className="text-[11px] text-zinc-400 truncate max-w-[200px]">
                    {deckB.track ? deckB.track.artist : "Select a track to load"}
                  </p>
                </div>
                {deckB.track && (
                  <div className="flex flex-col items-end gap-1 font-mono">
                    <span className="bg-amber-950 text-amber-500 border border-amber-900 text-[10px] px-2 py-0.5 rounded font-black">
                      {deckB.bpm} BPM | {getDisplayKey(deckB)}
                    </span>
                    {deckB.transposeOffset !== 0 && (
                      <button 
                        onClick={handleResetKeyB}
                        className="text-[8px] text-zinc-500 hover:text-zinc-300 transition-colors underline cursor-pointer"
                        title="Reset manual key transposition"
                      >
                        Reset Key Transpose
                      </button>
                    )}
                  </div>
                )}
              </div>

              {/* Key Pitch Lock & Auto Pitch / Key Sync for Deck B */}
              {deckB.track && (
                <div className="bg-zinc-950/60 p-2.5 rounded border border-zinc-850/80 flex flex-col gap-2 text-3xs text-zinc-400">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-1.5">
                      <input
                        type="checkbox"
                        id="deck-b-keylock"
                        checked={deckB.autoStretch}
                        onChange={(e) => setDeckB(prev => ({ ...prev, autoStretch: e.target.checked }))}
                        className="accent-amber-500 h-3 w-3 rounded border-zinc-800 bg-zinc-950 cursor-pointer"
                      />
                      <label htmlFor="deck-b-keylock" className="cursor-pointer font-bold select-none text-[9px] uppercase tracking-wider">
                        Key Lock (Auto Stretch)
                      </label>
                    </div>
                    <span className="text-zinc-500 font-mono">
                      {deckB.autoStretch ? "🔒 Master Tempo Active" : "🎸 Pitch Unlocked"}
                    </span>
                  </div>

                  {/* Key Sync / Auto Pitch Button */}
                  {deckA.track && (
                    <button
                      onClick={handleKeySyncB}
                      className="flex items-center justify-center gap-1.5 py-1.5 px-3 bg-cyan-950/40 hover:bg-cyan-950/70 border border-cyan-900 text-cyan-400 text-3xs font-bold uppercase rounded tracking-wider cursor-pointer transition-colors mt-1"
                      title="Sync B key signature to harmoniously match A"
                    >
                      <Sparkles size={10} className="text-cyan-400" />
                      Auto Pitch (Key Sync to {getDisplayKey(deckA).split(" ")[0]})
                    </button>
                  )}
                </div>
              )}
              
              <div className="flex gap-2 items-center">
                <select
                  onChange={(e) => {
                    const tr = tracks.find(t => t.id === e.target.value);
                    if (tr) loadTrackToDeck(tr, "B");
                  }}
                  value={deckB.track?.id || ""}
                  className="bg-zinc-950 border border-zinc-800 rounded px-2 py-1 text-xs text-zinc-300 flex-1 focus:ring-1 focus:ring-amber-500 focus:outline-none"
                  id="deck-b-select"
                >
                  <option value="" disabled>Load Song to Deck B...</option>
                  {tracks.map(t => (
                    <option key={t.id} value={t.id}>{t.title} ({t.bpm} BPM - {t.camelotKey})</option>
                  ))}
                </select>
                <button
                  onClick={() => {
                    initAudioContext();
                    setDeckB(prev => ({ ...prev, isMuted: !prev.isMuted }));
                  }}
                  className={`p-1.5 border rounded cursor-pointer transition-all ${
                    !deckB.isMuted 
                      ? "bg-amber-950 border-amber-800 text-amber-500" 
                      : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                  }`}
                  title={deckB.isMuted ? "Unmute Beat Click" : "Mute Beat Click"}
                  id="deck-b-mute"
                >
                  {deckB.isMuted ? <VolumeX size={15} /> : <Volume2 size={15} />}
                </button>
              </div>
            </div>
          </div>

          {/* Video stream monitors and Scrolling vertical beatgrids */}
          <div className="space-y-4 bg-zinc-900/35 border border-zinc-850 p-4 rounded-xl">
            {/* DECK A VISUAL GROUP */}
            <div className="space-y-3">
              <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 border-b border-zinc-850 pb-2">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse"></span>
                  <span className="text-[10px] font-bold uppercase tracking-wider text-zinc-300">Deck A Video Monitor</span>
                  {isSearchingA && (
                    <span className="text-[9px] text-zinc-500 animate-pulse">(Connecting Stream...)</span>
                  )}
                </div>
                <div className="flex items-center gap-2 w-full sm:w-auto">
                  <div className="relative flex-1 sm:flex-initial">
                    <input
                      type="text"
                      placeholder="Override YouTube Link..."
                      value={customUrlA}
                      onChange={(e) => setCustomUrlA(e.target.value)}
                      className="bg-zinc-950 border border-zinc-800 rounded pl-2 pr-6 py-0.5 text-[9px] text-zinc-300 w-full sm:w-[180px] focus:outline-none focus:border-cyan-500"
                    />
                    <button
                      onClick={() => handleCustomUrlLoad("A")}
                      className="absolute right-1.5 top-1 text-cyan-400 hover:text-cyan-300 transition-colors"
                    >
                      <Search size={9} />
                    </button>
                  </div>
                  <button
                    onClick={() => setShowVideoA(!showVideoA)}
                    className={`p-1 border rounded text-[9px] font-bold transition-all ${
                      showVideoA ? "border-cyan-800 text-cyan-400 bg-cyan-950/20" : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                    }`}
                  >
                    <Tv size={11} />
                  </button>
                </div>
              </div>

              {showVideoA && youtubeIdA && (
                <div className="relative aspect-video max-h-[140px] sm:max-h-[180px] w-full mx-auto bg-black rounded-lg overflow-hidden border border-cyan-950">
                  <div id="youtube-player-deck-a" className="w-full h-full"></div>
                </div>
              )}

              {/* DECK A VISUALIZER CANVAS */}
              <div className="relative">
                <canvas
                  ref={canvasARef}
                  width={800}
                  height={80}
                  className="w-full h-[80px] rounded border border-zinc-800 shadow-inner"
                />
                <div className="absolute top-2 right-3 flex items-center gap-2 bg-zinc-950/80 px-2 py-0.5 rounded text-[9px] font-mono text-cyan-400">
                  <span>SPEED: {deckA.bpm} BPM</span>
                  {deckA.pitch !== 0 && <span>({deckA.pitch > 0 ? "+" : ""}{deckA.pitch.toFixed(1)}%)</span>}
                </div>
              </div>

              {/* Deck A Horizontal Seekable Structure Timeline */}
              {renderTimeline("A")}
            </div>

            {/* DECK B VISUAL GROUP */}
            <div className="space-y-3">
              <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 border-b border-zinc-850 pb-2">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse"></span>
                  <span className="text-[10px] font-bold uppercase tracking-wider text-zinc-300">Deck B Video Monitor</span>
                  {isSearchingB && (
                    <span className="text-[9px] text-zinc-500 animate-pulse">(Connecting Stream...)</span>
                  )}
                </div>
                <div className="flex items-center gap-2 w-full sm:w-auto">
                  <div className="relative flex-1 sm:flex-initial">
                    <input
                      type="text"
                      placeholder="Override YouTube Link..."
                      value={customUrlB}
                      onChange={(e) => setCustomUrlB(e.target.value)}
                      className="bg-zinc-950 border border-zinc-800 rounded pl-2 pr-6 py-0.5 text-[9px] text-zinc-300 w-full sm:w-[180px] focus:outline-none focus:border-amber-500"
                    />
                    <button
                      onClick={() => handleCustomUrlLoad("B")}
                      className="absolute right-1.5 top-1 text-amber-400 hover:text-amber-300 transition-colors"
                    >
                      <Search size={9} />
                    </button>
                  </div>
                  <button
                    onClick={() => setShowVideoB(!showVideoB)}
                    className={`p-1 border rounded text-[9px] font-bold transition-all ${
                      showVideoB ? "border-amber-800 text-amber-400 bg-amber-950/20" : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                    }`}
                  >
                    <Tv size={11} />
                  </button>
                </div>
              </div>

              {showVideoB && youtubeIdB && (
                <div className="relative aspect-video max-h-[140px] sm:max-h-[180px] w-full mx-auto bg-black rounded-lg overflow-hidden border border-amber-950">
                  <div id="youtube-player-deck-b" className="w-full h-full"></div>
                </div>
              )}

              {/* DECK B VISUALIZER CANVAS */}
              <div className="relative">
                <canvas
                  ref={canvasBRef}
                  width={800}
                  height={80}
                  className="w-full h-[80px] rounded border border-zinc-800 shadow-inner"
                />
                <div className="absolute top-2 right-3 flex items-center gap-2 bg-zinc-950/80 px-2 py-0.5 rounded text-[9px] font-mono text-amber-400">
                  <span>SPEED: {deckB.bpm} BPM</span>
                  {deckB.pitch !== 0 && <span>({deckB.pitch > 0 ? "+" : ""}{deckB.pitch.toFixed(1)}%)</span>}
                </div>
              </div>

              {/* Deck B Horizontal Seekable Structure Timeline */}
              {renderTimeline("B")}
            </div>
          </div>

          {/* Control Hardware Panel: Play/Pause, Pitch Faders, Nudge Controls */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 bg-zinc-900/60 p-4 border border-zinc-850 rounded-xl">
            {/* Pitch Fader A */}
            <div className="flex flex-col justify-center items-center p-3 border-b md:border-b-0 md:border-r border-zinc-850 pb-5 md:pb-3">
              <span className="text-[10px] text-cyan-400 font-bold tracking-widest uppercase mb-1">DECK A Speed Fader</span>
              <div className="flex items-center gap-3 w-full px-4">
                <span className="text-[9px] font-mono text-zinc-500">-8%</span>
                <input
                  type="range"
                  min="-8"
                  max="8"
                  step="0.05"
                  value={deckA.pitch}
                  onChange={(e) => {
                    initAudioContext();
                    const pVal = parseFloat(e.target.value);
                    setDeckA(prev => ({ ...prev, pitch: pVal }));
                  }}
                  className="flex-1 accent-cyan-500 h-1 bg-zinc-850 rounded-lg appearance-none cursor-pointer"
                  id="deck-a-pitch-range"
                />
                <span className="text-[9px] font-mono text-zinc-500">+8%</span>
              </div>
              <div className="text-xs font-mono text-zinc-300 mt-2">
                BPM: <strong className="text-cyan-400">{deckA.bpm.toFixed(2)}</strong>
                {deckA.pitch !== 0 && <span className="text-zinc-500 text-[9px] ml-1">({deckA.pitch > 0 ? "+" : ""}{deckA.pitch.toFixed(2)}%)</span>}
              </div>
              <button
                onClick={() => setDeckA(prev => ({ ...prev, pitch: 0 }))}
                className="text-[9px] uppercase tracking-widest text-zinc-500 hover:text-zinc-300 transition-colors mt-2 underline cursor-pointer"
              >
                Reset Pitch
              </button>
            </div>

            {/* System Controls - Center Panel */}
            <div className="flex flex-col justify-center items-center gap-4 py-2 md:py-0">
              <div className="flex items-center gap-3">
                {/* Play / Pause Toggle */}
                <button
                  onClick={() => {
                    initAudioContext();
                    setIsPlaying(!isPlaying);
                  }}
                  className={`p-3.5 rounded-full cursor-pointer shadow-lg transition-all transform hover:scale-105 active:scale-95 ${
                    isPlaying 
                      ? "bg-red-600 hover:bg-red-500 text-white" 
                      : "bg-emerald-600 hover:bg-emerald-500 text-white"
                  }`}
                  title={isPlaying ? "Pause Engines" : "Start Beatgrid Roll"}
                  id="play-pause-btn"
                >
                  {isPlaying ? <Pause size={20} /> : <Play size={20} />}
                </button>

                {/* Reset Positions */}
                <button
                  onClick={handleReset}
                  className="p-3 bg-zinc-800 hover:bg-zinc-700 text-zinc-300 hover:text-white rounded-full transition-all cursor-pointer border border-zinc-700"
                  title="Reset Alignment & Offsets"
                  id="reset-align-btn"
                >
                  <RotateCcw size={15} />
                </button>
              </div>

              {/* Sync & Audio Volume Slider */}
              <div className="flex flex-col items-center gap-2 w-full px-6">
                <button
                  onClick={handleAutoSync}
                  className="flex items-center justify-center gap-2 w-full py-2 px-3 bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 text-[10px] font-bold uppercase rounded-lg tracking-widest transition-all cursor-pointer"
                  title="Instantly Match Deck B to Deck A BPM and Phase alignment"
                  id="sync-beats-btn"
                >
                  <Zap size={12} className="fill-emerald-400" />
                  Sync Beats (Auto Align)
                </button>

                {/* Audio volume slider */}
                <div className="flex items-center gap-2 w-full mt-1.5 justify-center">
                  <Volume2 size={10} className="text-zinc-500" />
                  <input
                    type="range"
                    min="0"
                    max="0.8"
                    step="0.05"
                    value={audioVolume}
                    onChange={(e) => setAudioVolume(parseFloat(e.target.value))}
                    className="w-24 accent-emerald-500 h-1 bg-zinc-850 rounded-lg appearance-none cursor-pointer"
                    title="Tone Beat Synths Volume"
                  />
                  <span className="text-[9px] font-mono text-zinc-500">Vol: {Math.round(audioVolume * 125)}%</span>
                </div>
              </div>
            </div>

            {/* Pitch Fader B & Manual Nudge */}
            <div className="flex flex-col justify-center items-center p-3 border-t md:border-t-0 md:border-l border-zinc-850 pt-5 md:pt-3">
              <span className="text-[10px] text-amber-400 font-bold tracking-widest uppercase mb-1">DECK B Speed Fader</span>
              <div className="flex items-center gap-3 w-full px-4">
                <span className="text-[9px] font-mono text-zinc-500">-8%</span>
                <input
                  type="range"
                  min="-8"
                  max="8"
                  step="0.05"
                  value={deckB.pitch}
                  onChange={(e) => {
                    initAudioContext();
                    const pVal = parseFloat(e.target.value);
                    setDeckB(prev => ({ ...prev, pitch: pVal }));
                  }}
                  className="flex-1 accent-amber-500 h-1 bg-zinc-850 rounded-lg appearance-none cursor-pointer"
                  id="deck-b-pitch-range"
                />
                <span className="text-[9px] font-mono text-zinc-500">+8%</span>
              </div>
              
              <div className="text-xs font-mono text-zinc-300 mt-2">
                BPM: <strong className="text-amber-400">{deckB.bpm.toFixed(2)}</strong>
                {deckB.pitch !== 0 && <span className="text-zinc-500 text-[9px] ml-1">({deckB.pitch > 0 ? "+" : ""}{deckB.pitch.toFixed(2)}%)</span>}
              </div>

              {/* Nudge Controls */}
              <div className="flex items-center gap-2 mt-2 w-full justify-center">
                <button
                  onClick={() => nudgeDeckB("backward")}
                  className="flex items-center gap-1 py-1 px-3 bg-zinc-800 hover:bg-zinc-700 border border-zinc-750 hover:border-zinc-600 rounded text-[9px] text-amber-400 font-bold uppercase transition-all cursor-pointer"
                  title="Slow down phase of Deck B temporarily"
                  id="nudge-back-btn"
                >
                  <ArrowLeft size={10} />
                  Nudge -
                </button>
                <button
                  onClick={() => nudgeDeckB("forward")}
                  className="flex items-center gap-1 py-1 px-3 bg-zinc-800 hover:bg-zinc-700 border border-zinc-750 hover:border-zinc-600 rounded text-[9px] text-amber-400 font-bold uppercase transition-all cursor-pointer"
                  title="Speed up phase of Deck B temporarily"
                  id="nudge-fwd-btn"
                >
                  Nudge +
                  <ArrowRight size={10} />
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB CONTENT: PERFORMANCE SAMPLER PADS */}
      {activeTab === "sampler" && (
        <div className="space-y-5 animate-fade-in">
          {/* Sampler Header & Controllers */}
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center bg-zinc-900 border border-zinc-850 p-4 rounded-xl gap-3">
            <div>
              <div className="flex items-center gap-1.5">
                <Radio className="text-purple-400 animate-pulse" size={15} />
                <h4 className="text-xs font-bold uppercase text-zinc-100 tracking-wider">Kaoss & Kitara Performance Studio</h4>
              </div>
              <p className="text-3xs text-zinc-400 leading-normal">
                Select an audio source pad, then hold & drag inside the Touch Vector XY Grid to trigger and sweep synthetic waves in real-time.
              </p>
            </div>

            <div className="flex items-center gap-2 w-full sm:w-auto">
              <button
                onClick={triggerAutoGrab}
                className="flex items-center justify-center gap-1 py-1.5 px-3 bg-purple-500/10 hover:bg-purple-500/20 border border-purple-500/30 text-purple-400 text-3xs font-bold uppercase rounded-lg tracking-wider transition-all cursor-pointer w-full sm:w-auto"
                title="Automatically grab synthesized tracks to fill up any unused sampler pads"
              >
                <Sparkles size={11} className="text-purple-400" />
                Auto Grab Samples
              </button>
              <button
                onClick={() => {
                  setSamplerPads(prev => prev.map(p => p.id > 4 ? { ...p, name: `Empty (P${p.id})`, isAssigned: false, recordedBuffer: null } : p));
                  setFeedbackMsg("Cleared all custom performance grabbed loops.");
                }}
                className="py-1.5 px-2.5 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 text-zinc-400 text-3xs font-bold uppercase rounded-lg transition-all cursor-pointer"
                title="Reset custom samples"
              >
                Clear Grabs
              </button>
            </div>
          </div>

          {/* Source Selection Pads Block */}
          <div className="space-y-2">
            <div className="text-[9px] uppercase tracking-wider text-zinc-500 font-mono">
              Step 1: Select Active Modulation Soundwave Source
            </div>
            
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {samplerPads.map((pad) => {
                const isActive = pad.id === selectedKaossPadId;
                return (
                  <div
                    key={pad.id}
                    onClick={() => setSelectedKaossPadId(pad.id)}
                    className={`relative bg-gradient-to-br p-3 rounded-xl border flex flex-col justify-between h-[90px] transition-all shadow-md cursor-pointer select-none group ${
                      isActive
                        ? "ring-2 ring-purple-500 border-purple-400 shadow-purple-950/20 scale-[0.98] bg-zinc-900"
                        : pad.isAssigned
                          ? "from-zinc-900/60 to-zinc-950/60 border-zinc-800 text-zinc-300 hover:bg-zinc-850"
                          : "from-zinc-950/30 to-zinc-950/50 border-zinc-900 text-zinc-600 hover:border-zinc-800"
                    }`}
                  >
                    {/* Top Pad Header */}
                    <div className="flex justify-between items-start w-full">
                      <span className={`text-[8px] font-mono font-black ${isActive ? "text-purple-400" : "text-zinc-500"}`}>
                        PAD 0{pad.id}
                      </span>
                      {isActive && (
                        <span className="text-[7px] bg-purple-500 text-zinc-950 font-black px-1.5 py-0.5 rounded tracking-widest uppercase">
                          KAOSS SRC
                        </span>
                      )}
                    </div>

                    {/* Pad Metadata / Name */}
                    <div className="flex flex-col justify-end">
                      <div className={`text-[10px] font-black uppercase truncate max-w-full ${
                        isActive ? "text-purple-300" : pad.isAssigned ? "text-zinc-200" : "text-zinc-500"
                      }`}>
                        {pad.name}
                      </div>
                      <span className="text-[8px] font-mono text-zinc-500 mt-0.5">
                        {pad.isAssigned ? `Synth: ${pad.synthType}` : "Click to select"}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Step 2: The Touch Vector XY Control Surface & Dashboard */}
          <div className="space-y-2">
            <div className="text-[9px] uppercase tracking-wider text-zinc-500 font-mono">
              Step 2: Drag finger or mouse on Touch Vector Surface
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
              {/* Touch Surface Area (Lg: 8 cols) */}
              <div className="lg:col-span-7 flex flex-col space-y-1.5">
                <div
                  onMouseDown={handleKaossPointerDown}
                  onMouseMove={handleKaossPointerMove}
                  onMouseUp={handleKaossPointerUp}
                  onMouseLeave={handleKaossPointerUp}
                  onTouchStart={handleKaossPointerDown}
                  onTouchMove={handleKaossPointerMove}
                  onTouchEnd={handleKaossPointerUp}
                  className="relative w-full h-[320px] bg-zinc-950 border-2 border-zinc-850 hover:border-purple-900/60 rounded-xl overflow-hidden cursor-crosshair select-none flex flex-col items-center justify-center group shadow-2xl"
                  style={{ touchAction: "none" }}
                >
                  {/* Web Audio Canvas Background Renderer */}
                  <canvas
                    ref={kaossCanvasRef}
                    width={500}
                    height={320}
                    className="absolute top-0 left-0 w-full h-full pointer-events-none"
                  />

                  {/* Corner Overlay Map Indicators */}
                  <div className="absolute top-3 left-4 text-[8px] font-mono text-zinc-600 uppercase pointer-events-none">
                    Pitch Frequencies [High] ↑
                  </div>
                  <div className="absolute bottom-3 left-4 text-[8px] font-mono text-zinc-600 uppercase pointer-events-none">
                    Pitch Frequencies [Low] ↓
                  </div>
                  <div className="absolute bottom-3 right-4 text-[8px] font-mono text-zinc-600 uppercase pointer-events-none text-right">
                    → Cutoff Sweep Filter [High]
                  </div>
                  <div className="absolute top-3 right-4 text-[8px] font-mono text-zinc-600 uppercase pointer-events-none text-right">
                    Filter Sweep [Low] ←
                  </div>

                  {/* Grid Crosshair Intersection Labels (if active) */}
                  {isKaossActive ? (
                    <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                      <div className="text-center space-y-1 bg-zinc-950/80 p-3 rounded-lg border border-purple-900/40 animate-pulse">
                        <span className="block text-[8px] text-zinc-500 uppercase tracking-widest">TOUCH DETECTED</span>
                        <div className="text-xs font-black font-mono text-purple-400">
                          X: {Math.round(kaossX * 100)}% | Y: {Math.round(kaossY * 100)}%
                        </div>
                      </div>
                    </div>
                  ) : (
                    <div className="text-center space-y-1.5 pointer-events-none z-10 p-6 bg-zinc-950/40 rounded-xl">
                      <Zap className="mx-auto text-purple-400 animate-pulse" size={24} />
                      <div className="text-2xs font-extrabold uppercase text-zinc-300 tracking-wider">
                        Touch Vector Matrix
                      </div>
                      <p className="text-[9px] text-zinc-500 max-w-xs mx-auto leading-relaxed">
                        Hold click & slide here to trigger the analog synthesis engine. Drag to modulate filters and oscillators.
                      </p>
                    </div>
                  )}
                </div>
              </div>

              {/* Telemetry Stats & Effects Deck (Lg: 5 cols) */}
              <div className="lg:col-span-5 flex flex-col justify-between bg-zinc-900/60 p-4 border border-zinc-850 rounded-xl space-y-4">
                {/* Section 1: Active Program Telemetry */}
                <div className="space-y-2.5">
                  <div className="flex items-center gap-1.5 pb-1.5 border-b border-zinc-850">
                    <Activity size={12} className="text-purple-400" />
                    <span className="text-[9px] font-black uppercase text-zinc-300 tracking-wider">Touch Vector HUD</span>
                  </div>

                  {/* Current Program */}
                  <div className="grid grid-cols-2 gap-2 text-3xs">
                    <div className="bg-zinc-950 p-2 rounded border border-zinc-900">
                      <span className="block text-zinc-500 uppercase tracking-wider font-mono">Synth Preset</span>
                      <strong className="text-purple-400 text-2xs block truncate mt-0.5">
                        {samplerPads.find(p => p.id === selectedKaossPadId)?.name}
                      </strong>
                    </div>
                    <div className="bg-zinc-950 p-2 rounded border border-zinc-900">
                      <span className="block text-zinc-500 uppercase tracking-wider font-mono">Synth Type</span>
                      <strong className="text-cyan-400 text-2xs block capitalize mt-0.5">
                        {samplerPads.find(p => p.id === selectedKaossPadId)?.synthType}
                      </strong>
                    </div>
                  </div>

                  {/* Live Coordinates readout */}
                  <div className="bg-zinc-950 p-3 rounded border border-zinc-900 space-y-2">
                    <span className="block text-zinc-500 text-[8px] uppercase tracking-widest font-mono">Modulation Coords</span>
                    <div className="grid grid-cols-2 gap-3 font-mono">
                      <div>
                        <div className="text-zinc-500 text-3xs">FILTER CUTOFF (X)</div>
                        <div className="text-sm font-black text-zinc-100">{Math.round(kaossX * 100)}%</div>
                      </div>
                      <div>
                        <div className="text-zinc-500 text-3xs">OSC PITCH (Y)</div>
                        <div className="text-sm font-black text-zinc-100">{Math.round(kaossY * 100)}%</div>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Section 2: Studio FX routing */}
                <div className="space-y-2.5">
                  <span className="text-zinc-500 text-[8px] uppercase tracking-widest font-mono block pb-1 border-b border-zinc-850">
                    Studio Auxiliary Routing (Kaoss FX)
                  </span>

                  <div className="grid grid-cols-2 gap-2">
                    <button
                      onClick={() => setKaossFxType("none")}
                      className={`py-1.5 px-3 rounded text-[9px] font-bold uppercase transition-all border cursor-pointer text-center ${
                        kaossFxType === "none"
                          ? "bg-purple-500 border-purple-400 text-zinc-950 font-extrabold"
                          : "bg-zinc-950 border-zinc-850 text-zinc-400 hover:text-white"
                      }`}
                    >
                      Dry Clean Route
                    </button>
                    <button
                      onClick={() => setKaossFxType("delay")}
                      className={`py-1.5 px-3 rounded text-[9px] font-bold uppercase transition-all border cursor-pointer text-center ${
                        kaossFxType === "delay"
                          ? "bg-purple-500 border-purple-400 text-zinc-950 font-extrabold"
                          : "bg-zinc-950 border-zinc-850 text-zinc-400 hover:text-white"
                      }`}
                    >
                      Pioneer Echo Delay
                    </button>
                  </div>
                </div>

                {/* Section 3: Tactile Program Map Cheat Sheet */}
                <div className="bg-zinc-950/80 p-3 rounded-lg border border-zinc-900 space-y-1.5">
                  <span className="text-zinc-500 text-[8px] font-mono uppercase block">Synthesis Matrix Map:</span>
                  <ul className="text-[8.5px] text-zinc-400 leading-normal space-y-1 list-none font-mono">
                    <li><strong className="text-rose-400">🟥 Kick/Bass:</strong> Dual sawtooth sub-wobbler (X: Lowpass, Y: Base pitch)</li>
                    <li><strong className="text-orange-400">🟧 Snare/Sweep:</strong> Noise filter sweeper (X: Cutoff sweep, Y: Filter Q)</li>
                    <li><strong className="text-amber-400">🟨 Hihat/Laser:</strong> Sine sweeps & metal ticks (X: Highpass, Y: Sweep pitch)</li>
                    <li><strong className="text-purple-400">🟪 Vocal/Chord:</strong> Chromatic chord triads & vocal formants (X: Scale root, Y: Sweeper)</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>

          {/* Step 3: Auto-Synthesized Playlist Loop Sampler */}
          <div className="space-y-3 mt-6 pt-5 border-t border-zinc-850/80">
            <div className="flex items-center gap-2">
              <Scissors size={14} className="text-purple-400" />
              <div className="text-[10px] uppercase tracking-wider text-zinc-300 font-bold font-sans">
                Step 3: Procedural Playlist Loop Sampler
              </div>
            </div>

            <div className="bg-zinc-900/40 border border-zinc-850/80 p-4 rounded-xl">
              <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-end">
                {/* Track Selector */}
                <div className="md:col-span-4 space-y-1">
                  <label className="block text-[8px] uppercase tracking-widest text-zinc-500 font-mono">Select Playlist Song</label>
                  <select
                    value={selectedLoopTrackId}
                    onChange={(e) => setSelectedLoopTrackId(e.target.value)}
                    className="w-full bg-zinc-950 border border-zinc-800 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:ring-1 focus:ring-purple-500 focus:outline-none"
                  >
                    <option value="" disabled>Choose track to sample...</option>
                    {tracks.map(t => (
                      <option key={t.id} value={t.id}>{t.title} ({t.bpm} BPM - {t.camelotKey})</option>
                    ))}
                  </select>
                </div>

                {/* Target Pad Selector */}
                <div className="md:col-span-2 space-y-1">
                  <label className="block text-[8px] uppercase tracking-widest text-zinc-500 font-mono">Target Pad Slot</label>
                  <select
                    value={selectedLoopPadId}
                    onChange={(e) => setSelectedLoopPadId(parseInt(e.target.value))}
                    className="w-full bg-zinc-950 border border-zinc-800 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:ring-1 focus:ring-purple-500 focus:outline-none"
                  >
                    {[1, 2, 3, 4, 5, 6, 7, 8].map(num => (
                      <option key={num} value={num}>Pad {num} {samplerPads.find(p => p.id === num)?.isAssigned ? `(${samplerPads.find(p => p.id === num)?.name.slice(0, 8)}...)` : "(Empty)"}</option>
                    ))}
                  </select>
                </div>

                {/* Loop Length */}
                <div className="md:col-span-3 space-y-1">
                  <label className="block text-[8px] uppercase tracking-widest text-zinc-500 font-mono">Loop Duration (Beats)</label>
                  <div className="grid grid-cols-4 gap-1">
                    {[2, 4, 8, 16].map(beats => (
                      <button
                        key={beats}
                        type="button"
                        onClick={() => setSelectedLoopBeats(beats)}
                        className={`py-1 px-1.5 border text-3xs font-mono font-bold rounded transition-colors cursor-pointer text-center ${
                          selectedLoopBeats === beats
                            ? "bg-purple-500 border-purple-400 text-zinc-950"
                            : "bg-zinc-950 border-zinc-800 text-zinc-400 hover:text-white"
                        }`}
                      >
                        {beats}B
                      </button>
                    ))}
                  </div>
                </div>

                {/* Generate Button */}
                <div className="md:col-span-3">
                  <button
                    onClick={() => {
                      const trackObj = tracks.find(t => t.id === selectedLoopTrackId);
                      if (trackObj) {
                        sampleLoopFromTrack(trackObj, selectedLoopPadId, selectedLoopBeats);
                      } else {
                        setFeedbackMsg("Please select a track first to generate a loop.");
                      }
                    }}
                    className="w-full flex items-center justify-center gap-1.5 py-1.5 px-4 bg-purple-500 hover:bg-purple-400 text-zinc-950 font-bold text-3xs uppercase tracking-wider rounded transition-all cursor-pointer shadow-md shadow-purple-950/20 active:scale-95"
                  >
                    <Scissors size={10} />
                    Synthesize & Load Loop
                  </button>
                </div>
              </div>

              <div className="mt-3 bg-zinc-950/60 p-2.5 rounded border border-zinc-850 text-4xs leading-normal text-zinc-500 font-mono">
                <span className="text-purple-400 font-bold block mb-0.5">🤖 AI SYNTHESIS NOTES:</span>
                Procedurally captures the song's key frequencies, rhythmic sync metrics, and structural energy levels to write a custom stereophonic waveform (e.g. Deep bouncing stabs for House, industrial roll for Techno, heavy LFO wobble for Dubstep, slow electric piano arps for Lofi). It locks to the target pads and loops continuously alongside active deck rolls.
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB CONTENT: INTERACTIVE SHAPEABLE ENERGY GRAPH */}
      {activeTab === "energy" && (
        <div className="animate-fade-in">
          <EnergyGraph
            bpmA={deckA.bpm}
            bpmB={deckB.bpm}
            pitchA={deckA.pitch}
            pitchB={deckB.pitch}
            isPlaying={isPlaying}
          />
        </div>
      )}

      {/* TAB CONTENT: RADIAL CONTROLLER & GESTURE VISUALIZER */}
      {activeTab === "radial" && (
        <div className="animate-fade-in bg-zinc-950 border border-zinc-850 p-5 rounded-xl shadow-2xl space-y-5">
          <div className="flex flex-col md:flex-row justify-between items-start md:items-center border-b border-zinc-850/60 pb-4 gap-4">
            <div className="space-y-1">
              <h3 className="text-sm font-black text-zinc-100 uppercase tracking-widest flex items-center gap-2">
                <Orbit className="text-emerald-400 animate-spin" size={16} />
                <span>Radial Gesture Console</span>
              </h3>
              <p className="text-3xs text-zinc-400 max-w-2xl leading-normal">
                An advanced circular chronograph interface displaying queued tracks as concentric, energy-colored waveforms. Use touch gestures or the virtual multi-touch mouse emulator pad to control BPM zoom, pitch, EQ balance, overlapping grid phase offset, crossfade, seeking, and volume in real-time.
              </p>
            </div>
          </div>

          <RadialController
            tracks={tracks}
            deckA={deckA}
            deckB={deckB}
            setDeckA={setDeckA}
            setDeckB={setDeckB}
            audioVolume={audioVolume}
            setAudioVolume={setAudioVolume}
            autoMixCrossfader={autoMixCrossfader}
            setAutoMixCrossfader={setAutoMixCrossfader}
            isPlaying={isPlaying}
            setIsPlaying={setIsPlaying}
            timeA={timeA}
            timeB={timeB}
            durationA={durationA}
            durationB={durationB}
            handleSeek={handleSeek}
            setFeedbackMsg={setFeedbackMsg}
          />
        </div>
      )}

      {/* TAB CONTENT: AUTOMATCHIC MIX SYSTEM */}
      {activeTab === "automix" && (
        <div className="space-y-6 animate-fade-in">
          {/* Main Control Panel Dashboard */}
          <div className="bg-gradient-to-br from-zinc-900 to-zinc-950 border border-zinc-850 p-5 rounded-xl space-y-5">
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
              <div className="space-y-1.5">
                <div className="flex items-center gap-2">
                  <span className={`w-2.5 h-2.5 rounded-full ${isAutoMixing ? "bg-amber-400 animate-ping" : "bg-zinc-600"}`}></span>
                  <h4 className="text-xs font-bold uppercase text-zinc-100 tracking-wider">Automatchic Mix Engine Dashboard</h4>
                </div>
                <p className="text-3xs text-zinc-400 max-w-2xl leading-normal">
                  The Automatchic Mix coordinates and unleashes ALL five professional performance tools simultaneously. It curates a harmonic playlist, locks beatgrids, matches keys, populates custom atmosphere samples, injects rhythmic live audio triggers, and performs seamless crossfading.
                </p>
              </div>

              <div className="flex items-center gap-3 w-full md:w-auto">
                {isAutoMixing ? (
                  <button
                    onClick={stopAutomatchicMix}
                    className="flex-1 md:flex-initial flex items-center justify-center gap-2 py-2.5 px-5 bg-red-600 hover:bg-red-500 text-white font-bold text-xs uppercase tracking-wider rounded-lg transition-all cursor-pointer shadow-md shadow-red-950/20 active:scale-95"
                    id="stop-automix-btn"
                  >
                    <Square size={13} className="fill-white text-white" />
                    Pause Automix
                  </button>
                ) : (
                  <button
                    onClick={startAutomatchicMix}
                    className="flex-1 md:flex-initial flex items-center justify-center gap-2 py-2.5 px-5 bg-gradient-to-r from-amber-500 to-orange-500 hover:from-amber-400 hover:to-orange-400 text-zinc-950 font-extrabold text-xs uppercase tracking-widest rounded-lg transition-all cursor-pointer shadow-lg shadow-amber-950/20 active:scale-95 animate-pulse"
                    id="start-automix-btn"
                  >
                    <Sparkles size={13} className="text-zinc-950 fill-zinc-950" />
                    Start Automatchic Mix
                  </button>
                )}

                {isAutoMixing && (
                  <button
                    onClick={skipToNextAutoMixTransition}
                    className="py-2.5 px-4 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 hover:border-zinc-600 text-zinc-200 text-xs font-bold uppercase tracking-wider rounded-lg transition-all cursor-pointer"
                    title="Instantly initiate transition crossfade"
                    id="skip-automix-btn"
                  >
                    Skip to Mix
                  </button>
                )}
              </div>
            </div>

            {/* Custom Session Duration Selector */}
            <div className="flex flex-wrap items-center gap-3 pt-3 border-t border-zinc-900 text-3xs text-zinc-400">
              <span className="font-mono uppercase tracking-widest text-zinc-500">Transition Interval:</span>
              <div className="flex bg-zinc-950 p-1 rounded-lg border border-zinc-900 gap-1">
                {[20, 30, 60, 120].map((sec) => (
                  <button
                    key={sec}
                    onClick={() => {
                      setAutoMixDuration(sec);
                      if (isAutoMixing) {
                        setAutoMixTimeRemaining(sec);
                        setFeedbackMsg(`Reset timer interval to ${sec}s for the next track transition.`);
                      }
                    }}
                    className={`px-3 py-1.5 rounded-md font-bold uppercase transition-all cursor-pointer ${
                      autoMixDuration === sec
                        ? "bg-amber-500 text-zinc-950 font-extrabold"
                        : "text-zinc-400 hover:text-white"
                    }`}
                  >
                    {sec}s {sec === 20 ? "(Blitz)" : sec === 30 ? "(Showcase)" : ""}
                  </button>
                ))}
              </div>
            </div>

            {/* LIVE AUTOMATION HUD DISPLAY */}
            {isAutoMixing && (
              <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 mt-4 pt-4 border-t border-zinc-900 animate-fade-in">
                {/* Countdown & Steps Panel */}
                <div className="lg:col-span-5 bg-zinc-950/60 p-4 rounded-xl border border-zinc-900 flex flex-col justify-between space-y-4">
                  <div className="space-y-1.5">
                    <span className="text-[10px] font-bold text-amber-400 tracking-wider font-mono">LIVE COUNTDOWN CLOCK</span>
                    <div className="flex items-baseline gap-3">
                      <div className="text-4xl font-black font-mono tracking-tighter text-zinc-100">
                        00:{autoMixTimeRemaining < 10 ? "0" : ""}{autoMixTimeRemaining}
                      </div>
                      <span className="text-3xs text-zinc-500 uppercase tracking-widest font-mono">Until Next Mix Phase</span>
                    </div>
                  </div>

                  {/* Progressive Automation Stages */}
                  <div className="space-y-2.5">
                    <div className="flex items-center justify-between text-3xs border-b border-zinc-900 pb-1">
                      <span className="text-zinc-500 uppercase tracking-widest font-mono">Automation Checklist</span>
                      <span className="text-amber-400 font-bold uppercase tracking-wider font-mono bg-amber-950/30 px-1.5 py-0.5 rounded border border-amber-900/30">
                        Active Loop
                      </span>
                    </div>

                    <div className="space-y-1.5 text-3xs font-mono">
                      {/* Step 1 */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 text-zinc-300">
                          <span className={`w-1.5 h-1.5 rounded-full ${autoMixTimeRemaining <= Math.round(autoMixDuration * 0.8) ? "bg-emerald-500" : "bg-zinc-700 animate-pulse"}`}></span>
                          <span>⚡ Auto-Sync Beats</span>
                        </div>
                        <span className={autoMixTimeRemaining <= Math.round(autoMixDuration * 0.8) ? "text-emerald-400 font-bold" : "text-zinc-600"}>
                          {autoMixTimeRemaining <= Math.round(autoMixDuration * 0.8) ? "✓ LOCKED" : `PENDING (-${Math.round(autoMixDuration * 0.2)}s)`}
                        </span>
                      </div>

                      {/* Step 2 */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 text-zinc-300">
                          <span className={`w-1.5 h-1.5 rounded-full ${autoMixTimeRemaining <= Math.round(autoMixDuration * 0.65) ? "bg-emerald-500" : "bg-zinc-700"}`}></span>
                          <span>🔑 Harmonic Auto-Pitch</span>
                        </div>
                        <span className={autoMixTimeRemaining <= Math.round(autoMixDuration * 0.65) ? "text-emerald-400 font-bold" : "text-zinc-600"}>
                          {autoMixTimeRemaining <= Math.round(autoMixDuration * 0.65) ? "✓ LOCKED" : `PENDING (-${Math.round(autoMixDuration * 0.35)}s)`}
                        </span>
                      </div>

                      {/* Step 3 */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 text-zinc-300">
                          <span className={`w-1.5 h-1.5 rounded-full ${autoMixTimeRemaining <= Math.round(autoMixDuration * 0.5) ? "bg-emerald-500" : "bg-zinc-700"}`}></span>
                          <span>🧬 AI Atmosphere Grab</span>
                        </div>
                        <span className={autoMixTimeRemaining <= Math.round(autoMixDuration * 0.5) ? "text-emerald-400 font-bold" : "text-zinc-600"}>
                          {autoMixTimeRemaining <= Math.round(autoMixDuration * 0.5) ? "✓ GRABBED" : `PENDING (-${Math.round(autoMixDuration * 0.5)}s)`}
                        </span>
                      </div>

                      {/* Step 4 */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 text-zinc-300">
                          <span className={`w-1.5 h-1.5 rounded-full ${autoMixTimeRemaining <= Math.round(autoMixDuration * 0.35) ? "bg-emerald-500" : "bg-zinc-700"}`}></span>
                          <span>🥁 Live Sampler FX Trick</span>
                        </div>
                        <span className={autoMixTimeRemaining <= Math.round(autoMixDuration * 0.35) ? "text-emerald-400 font-bold" : "text-zinc-600"}>
                          {autoMixTimeRemaining <= Math.round(autoMixDuration * 0.35) ? "✓ INJECTED" : `PENDING (-${Math.round(autoMixDuration * 0.65)}s)`}
                        </span>
                      </div>

                      {/* Step 5 */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 text-zinc-300">
                          <span className={`w-1.5 h-1.5 rounded-full ${autoMixTimeRemaining <= 5 ? "bg-emerald-500 animate-ping" : "bg-zinc-700"}`}></span>
                          <span>🎚️ Active Crossfade & Energy</span>
                        </div>
                        <span className={autoMixTimeRemaining <= 5 ? "text-amber-400 font-bold" : "text-zinc-600"}>
                          {autoMixTimeRemaining <= 5 ? "⚡ TRANSITIONING" : "CUEING"}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Dashboard active engines details */}
                <div className="lg:col-span-7 bg-zinc-950/60 p-4 rounded-xl border border-zinc-900 space-y-4 flex flex-col justify-between">
                  <div className="space-y-3">
                    <span className="text-[10px] font-bold text-amber-400 tracking-wider font-mono">AUTOMIX CONSOLE HUD STATUS</span>
                    <div className="text-3xs font-mono bg-zinc-900 border border-zinc-850 p-2 rounded text-amber-300 leading-normal animate-pulse">
                      🔊 {autoMixStatus}
                    </div>

                    <div className="grid grid-cols-2 gap-3 text-3xs font-mono">
                      <div className="p-2 border border-zinc-900 rounded bg-zinc-950/20">
                        <div className="text-zinc-500">DECK A (Playing)</div>
                        <div className="text-zinc-200 font-bold truncate">{deckA.track?.title || "Empty"}</div>
                        <div className="text-zinc-400 text-[9px]">{deckA.bpm} BPM | {deckA.track?.camelotKey}</div>
                        <div className="text-[9px] mt-1 font-bold text-cyan-400">Vol: {Math.round(getDeckVolumeA() * 100)}%</div>
                      </div>

                      <div className="p-2 border border-zinc-900 rounded bg-zinc-950/20">
                        <div className="text-zinc-500">DECK B (Cueing)</div>
                        <div className="text-zinc-200 font-bold truncate">{deckB.track?.title || "Empty"}</div>
                        <div className="text-zinc-400 text-[9px]">{deckB.bpm} BPM | {getDisplayKey(deckB)}</div>
                        <div className="text-[9px] mt-1 font-bold text-amber-400">Vol: {Math.round(getDeckVolumeB() * 100)}%</div>
                      </div>
                    </div>
                  </div>

                  {/* Crossfader Visual Widget */}
                  <div className="space-y-1.5 pt-3 border-t border-zinc-900">
                    <div className="flex justify-between items-center text-[9px] font-mono font-bold text-zinc-500">
                      <span>DECK A VOLUME ({Math.round((100 - autoMixCrossfader) / 2)}%)</span>
                      <span>ACTIVE AUTOMIX CROSSFADER</span>
                      <span>DECK B VOLUME ({Math.round((100 + autoMixCrossfader) / 2)}%)</span>
                    </div>

                    <div className="relative bg-zinc-900 border border-zinc-850 p-2.5 rounded-lg flex items-center justify-center">
                      <div className="absolute left-1/2 -translate-x-1/2 w-[1px] h-full bg-zinc-850"></div>
                      <div className="w-full relative h-1 bg-zinc-950 rounded overflow-hidden">
                        <div 
                          className="absolute h-full bg-gradient-to-r from-cyan-500 via-amber-500 to-orange-500"
                          style={{
                            left: "0",
                            width: `${(autoMixCrossfader + 100) / 2}%`,
                            transition: "width 0.3s ease-out"
                          }}
                        ></div>
                      </div>
                      
                      {/* Physical slider handle */}
                      <div 
                        className="absolute w-4 h-6 rounded bg-zinc-200 border-2 border-amber-500 shadow shadow-amber-950/40"
                        style={{
                          left: `calc(${(autoMixCrossfader + 100) / 2}% - 8px)`,
                          transition: "left 0.3s ease-out"
                        }}
                      ></div>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Curated Pro-Grade Harmonic Playlist Sequence */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-bold text-zinc-400 tracking-wider uppercase font-sans">Curated Harmonic Playlist Sequence</span>
              {autoMixPlaylist.length > 0 && (
                <span className="text-3xs text-zinc-500 font-mono">
                  {autoMixPlaylist.length} Tracks Selected
                </span>
              )}
            </div>

            {autoMixPlaylist.length > 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
                {autoMixPlaylist.map((track, idx) => {
                  const isCurrent = idx === autoMixCurrentIndex;
                  const isNext = idx === (autoMixCurrentIndex + 1) % autoMixPlaylist.length;
                  
                  return (
                    <div
                      key={`${track.id}-${idx}`}
                      className={`p-3.5 rounded-xl border transition-all relative flex flex-col justify-between gap-4 bg-zinc-900/40 ${
                        isCurrent
                          ? "border-amber-500/80 shadow-md shadow-amber-950/15 ring-1 ring-amber-500/30"
                          : isNext
                            ? "border-cyan-500/50 hover:border-cyan-500/70"
                            : "border-zinc-850 hover:border-zinc-800"
                      }`}
                    >
                      <div className="space-y-1">
                        <div className="flex justify-between items-start gap-2">
                          <span className="text-[8px] font-mono font-black text-zinc-500">MIX STEP 0{idx + 1}</span>
                          {isCurrent ? (
                            <span className="bg-amber-950 text-amber-400 border border-amber-900/40 text-[8px] font-black uppercase px-1.5 py-0.5 rounded tracking-widest animate-pulse">
                              NOW PLAYING
                            </span>
                          ) : isNext ? (
                            <span className="bg-cyan-950 text-cyan-400 border border-cyan-900/40 text-[8px] font-black uppercase px-1.5 py-0.5 rounded tracking-widest">
                              CUE NEXT
                            </span>
                          ) : (
                            <span className="bg-zinc-950 text-zinc-500 border border-zinc-900 text-[8px] font-black uppercase px-1.5 py-0.5 rounded tracking-widest">
                              QUEUED
                            </span>
                          )}
                        </div>
                        <h5 className="text-xs font-bold text-zinc-100 truncate">{track.title}</h5>
                        <p className="text-3xs text-zinc-400">{track.artist}</p>
                      </div>

                      <div className="flex justify-between items-center text-3xs font-mono border-t border-zinc-900/60 pt-2.5">
                        <div className="flex gap-2 text-zinc-500">
                          <span>{track.bpm} BPM</span>
                          <span>•</span>
                          <span>{track.camelotKey}</span>
                        </div>
                        <div className="text-zinc-400 font-semibold text-[10px]">
                          {idx < autoMixPlaylist.length - 1 ? (
                            <span className="text-emerald-400/80 font-bold">
                              {compareTracks(track, autoMixPlaylist[idx + 1]).overallScore}% Flow
                            </span>
                          ) : (
                            <span className="text-purple-400/80 font-bold">
                              {compareTracks(track, autoMixPlaylist[0]).overallScore}% Loop Flow
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="border border-dashed border-zinc-850 p-10 rounded-xl text-center space-y-3 bg-zinc-900/10">
                <p className="text-3xs text-zinc-500 font-mono">No active playlist loaded.</p>
                <p className="text-2xs text-zinc-400 leading-normal max-w-sm mx-auto">
                  Click the <strong>Start Automatchic Mix</strong> button above! The system will scan your library, calculate optimum harmonic sequences, and begin streaming.
                </p>
              </div>
            )}
          </div>
        </div>
      )}
      </>)}
      {/* 🛠️ EXPOSED DEVELOPER CONTROL API EXPLORER */}
      {renderApiHub()}
    </div>
  );

  // --- TIME SLIDER TIMELINE & CUE MARKERS RENDERING ---
  function renderTimeline(deck: "A" | "B") {
    const time = deck === "A" ? timeA : timeB;
    const duration = deck === "A" ? durationA : durationB;
    const cues = deck === "A" ? deckACues : deckBCues;
    const accent = deck === "A" ? "text-cyan-400 bg-cyan-500" : "text-amber-400 bg-amber-500";
    const barAccent = deck === "A" ? "bg-cyan-500" : "bg-amber-500";
    const hoverAccent = deck === "A" ? "hover:bg-cyan-950/20 hover:border-cyan-500/50" : "hover:bg-amber-950/20 hover:border-amber-500/50";
    const borderAccent = deck === "A" ? "border-cyan-800" : "border-zinc-850";
    const track = deck === "A" ? deckA.track : deckB.track;

    if (!track) return null;

    const elements = getSongElements(track, duration);
    const progressPercent = duration > 0 ? (time / duration) * 100 : 0;

    return (
      <div className="space-y-2 mt-2 bg-zinc-950/40 p-3 rounded-lg border border-zinc-850/60">
        <div className="flex items-center justify-between text-[10px] font-mono text-zinc-400">
          <span className="font-bold flex items-center gap-1">
            <Disc size={11} className={`animate-spin-slow ${deck === "A" ? "text-cyan-400" : "text-amber-400"}`} />
            STRUCTURE MAP & TIMELINE
          </span>
          <span className="bg-zinc-900 px-1.5 py-0.5 rounded text-zinc-300 font-bold border border-zinc-800">
            {formatTime(time)} / {formatTime(duration)}
          </span>
        </div>

        {/* Horizontal Timeline Track */}
        <div className="relative h-6 bg-zinc-900 rounded-lg overflow-hidden border border-zinc-800 flex items-center cursor-pointer select-none"
          onClick={(e) => {
            const rect = e.currentTarget.getBoundingClientRect();
            const clickPercent = (e.clientX - rect.left) / rect.width;
            handleSeek(deck, clickPercent * duration);
          }}
        >
          {/* Structural segments background */}
          {elements.map((el, idx) => {
            const startPercent = (el.start / duration) * 100;
            const widthPercent = ((el.end - el.start) / duration) * 100;
            return (
              <div 
                key={idx}
                style={{ left: `${startPercent}%`, width: `${widthPercent}%` }}
                className={`absolute top-0 bottom-0 opacity-15 ${el.color} border-r border-zinc-800/40`}
                title={`${el.name}: ${el.desc}`}
              />
            );
          })}

          {/* Current play progress bar overlay */}
          <div 
            style={{ width: `${progressPercent}%` }} 
            className={`absolute top-0 bottom-0 ${barAccent} opacity-20 pointer-events-none`}
          />

          {/* Render Element Tag boundary markers inside progress bar */}
          {elements.map((el, idx) => {
            const startPercent = (el.start / duration) * 100;
            if (startPercent === 0) return null;
            return (
              <div 
                key={idx}
                style={{ left: `${startPercent}%` }}
                className="absolute top-0 bottom-0 w-[1px] bg-zinc-700/60 pointer-events-none"
              />
            );
          })}

          {/* Cue point flag indicators */}
          {cues.map((cueVal, index) => {
            if (cueVal === null) return null;
            const cuePercent = (cueVal / duration) * 100;
            return (
              <div 
                key={index}
                style={{ left: `${cuePercent}%` }}
                className="absolute top-0 bottom-0 w-2 -ml-1 flex flex-col items-center pointer-events-none z-10"
                title={`Cue ${index + 1}: ${formatTime(cueVal)}`}
              >
                <div className={`w-2 h-2 rounded-full ${barAccent} border border-white shadow`} />
                <div className={`w-[1px] h-3 ${deck === "A" ? "bg-cyan-400" : "bg-amber-400"}`} />
                <span className="text-[7px] font-bold text-white bg-zinc-950 px-0.5 rounded -mt-1 scale-75">C{index + 1}</span>
              </div>
            );
          })}

          {/* Central Playhead seeker node */}
          <div 
            style={{ left: `${progressPercent}%` }} 
            className="absolute top-0 bottom-0 w-[3px] bg-red-500 shadow-lg -ml-[1.5px] pointer-events-none z-20"
          >
            <div className="absolute -top-1 -left-1 w-2.5 h-2.5 bg-red-500 rounded-full border border-white" />
          </div>
        </div>

        {/* Structure label text under timeline */}
        <div className="relative h-4 text-[9px] text-zinc-500 font-mono">
          {elements.map((el, idx) => {
            const startPercent = (el.start / duration) * 100;
            const widthPercent = ((el.end - el.start) / duration) * 100;
            return (
              <div 
                key={idx}
                style={{ left: `${startPercent}%`, width: `${widthPercent}%` }}
                className="absolute truncate text-center px-1 font-bold tracking-wider hover:text-zinc-300 transition-colors pointer-events-none"
              >
                {el.name}
              </div>
            );
          })}
        </div>

        {/* Interactive Cue Markers Panel & Element details */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1">
          {cues.map((cueVal, index) => {
            const hasCue = cueVal !== null;
            return (
              <div 
                key={index} 
                className={`relative flex items-center justify-between border rounded p-1.5 text-[10px] font-mono transition-all ${
                  hasCue 
                    ? `${borderAccent} bg-zinc-900 text-zinc-200 ${hoverAccent}`
                    : "border-zinc-850 bg-zinc-950/20 text-zinc-500 hover:border-zinc-800"
                }`}
              >
                {hasCue ? (
                  <>
                    <button 
                      onClick={() => handleSeek(deck, cueVal)}
                      className="flex-1 text-left flex items-center gap-1.5 font-bold cursor-pointer focus:outline-none"
                    >
                      <span className={`w-1.5 h-1.5 rounded-full ${barAccent}`} />
                      CUE {index + 1} ({formatTime(cueVal)})
                    </button>
                    <button 
                      onClick={(e) => {
                        e.stopPropagation();
                        if (deck === "A") {
                          setDeckACues(prev => prev.map((v, i) => i === index ? null : v));
                        } else {
                          setDeckBCues(prev => prev.map((v, i) => i === index ? null : v));
                        }
                        setFeedbackMsg(`Cleared Cue ${index + 1} on Deck ${deck}.`);
                      }}
                      className="text-zinc-500 hover:text-red-400 transition-colors cursor-pointer px-1 text-[11px] font-bold"
                      title="Clear cue point"
                    >
                      ×
                    </button>
                  </>
                ) : (
                  <button 
                    onClick={() => {
                      if (deck === "A") {
                        setDeckACues(prev => prev.map((v, i) => i === index ? Math.round(time) : v));
                      } else {
                        setDeckBCues(prev => prev.map((v, i) => i === index ? Math.round(time) : v));
                      }
                      setFeedbackMsg(`Set Cue ${index + 1} on Deck ${deck} to ${formatTime(time)}.`);
                    }}
                    className="w-full text-center font-semibold text-zinc-600 hover:text-zinc-400 cursor-pointer focus:outline-none flex items-center justify-center gap-1"
                  >
                    + Set Cue {index + 1}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  // --- MULTI-DEVICE SCREEN LAYOUTS ---
  function renderTactileDeckScreen(deck: "A" | "B") {
    const isDeckA = deck === "A";
    const state = isDeckA ? deckA : deckB;
    const setState = isDeckA ? setDeckA : setDeckB;
    const cues = isDeckA ? deckACues : deckBCues;
    const setCues = isDeckA ? setDeckACues : setDeckBCues;
    const canvasRef = isDeckA ? canvasARef : canvasBRef;
    const duration = isDeckA ? durationA : durationB;
    const time = isDeckA ? timeA : timeB;
    const showVideo = isDeckA ? showVideoA : showVideoB;
    const setShowVideo = isDeckA ? setShowVideoA : setShowVideoB;
    const youtubeId = isDeckA ? youtubeIdA : youtubeIdB;
    const setCustomUrl = isDeckA ? setCustomUrlA : setCustomUrlB;
    const customUrl = isDeckA ? customUrlA : customUrlB;

    const accentColor = isDeckA ? "cyan" : "amber";
    const themeColorText = isDeckA ? "text-cyan-400" : "text-amber-500";
    const themeBg = isDeckA ? "bg-cyan-500" : "bg-amber-500";
    const themeBorder = isDeckA ? "border-cyan-800" : "border-amber-800";
    const playerReady = isDeckA ? playerAReady : playerBReady;
    const playerRef = isDeckA ? playerARef : playerBRef;

    return (
      <div className="space-y-5 animate-fade-in bg-zinc-950 p-6 border border-zinc-850 rounded-2xl shadow-xl">
        <div className="flex justify-between items-center border-b border-zinc-850 pb-3">
          <div className="flex items-center gap-2">
            <Disc className={`animate-spin-slow ${themeColorText}`} size={18} />
            <h3 className="text-sm font-black uppercase tracking-widest text-white">
              Tactile Platter Console &mdash; Deck {deck}
            </h3>
          </div>
          <span className={`text-[10px] font-mono px-2 py-0.5 rounded font-black bg-zinc-900 text-zinc-300 border border-zinc-800`}>
            {state.bpm.toFixed(2)} BPM | {getDisplayKey(state)}
          </span>
        </div>

        {/* Video Player Display */}
        {showVideo && youtubeId && (
          <div className="relative aspect-video max-h-[160px] w-full mx-auto bg-black rounded-xl overflow-hidden border border-zinc-800">
            <div id={`youtube-player-deck-${deck.toLowerCase()}`} className="w-full h-full"></div>
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-center">
          {/* Circular Vinyl Platter Placed in Left Side of Large Screen */}
          <div className="lg:col-span-5 flex flex-col items-center justify-center space-y-3">
            <div 
              className={`relative w-44 h-44 rounded-full bg-black border-4 border-zinc-800 shadow-2xl flex items-center justify-center overflow-hidden cursor-pointer select-none`}
              onClick={() => {
                initAudioContext();
                const nextPlaying = !isPlaying;
                setIsPlaying(nextPlaying);
                sendWSState({ isPlaying: nextPlaying });
              }}
            >
              {/* Concentric grooved vinyl stripes */}
              <div className="absolute inset-2 border border-zinc-900/50 rounded-full" />
              <div className="absolute inset-4 border border-zinc-800/40 rounded-full" />
              <div className="absolute inset-8 border border-zinc-900/60 rounded-full" />
              <div className="absolute inset-12 border border-zinc-800/50 rounded-full" />
              <div className="absolute inset-16 border border-zinc-900/40 rounded-full" />
              
              {/* Spinning Platter Core */}
              <div 
                className={`absolute inset-0 flex items-center justify-center transition-transform duration-1000`}
                style={{
                  transform: isPlaying ? `rotate(${(time * (state.bpm / 60) * 90) % 360}deg)` : "rotate(0deg)",
                }}
              >
                {/* Visual Strobo stripes */}
                <div className="absolute top-0 bottom-0 w-1 bg-zinc-850 opacity-40" />
                <div className="absolute left-0 right-0 h-1 bg-zinc-850 opacity-40" />
                
                {/* Center Platter Label */}
                <div className="w-14 h-14 rounded-full bg-zinc-900 border border-zinc-700 flex flex-col items-center justify-center text-center p-1 z-10 shadow-lg">
                  <span className={`text-[7px] font-black uppercase ${themeColorText}`}>DECK {deck}</span>
                  <span className="text-[6px] text-zinc-400 font-bold truncate max-w-[44px]">
                    {state.track ? state.track.title : "No Song"}
                  </span>
                </div>
              </div>

              {/* Red Tone-Arm Indicator Needle */}
              <div className="absolute top-0 bottom-1/2 right-[49%] w-[2px] bg-red-500 origin-bottom z-20 pointer-events-none" />
            </div>

            <p className="text-[10px] text-zinc-500 font-mono tracking-wider">
              {isPlaying ? "💿 ROTATING LATENCY CLOCKED" : "⏸️ ENGAGEMENT STOPPED"}
            </p>
          </div>

          {/* Platter Deck Parameters: Cue points, pitch faders, play buttons */}
          <div className="lg:col-span-7 space-y-4">
            {/* Play & Sync Controls row */}
            <div className="flex gap-2.5">
              <button
                onClick={() => {
                  initAudioContext();
                  const nextPlay = !isPlaying;
                  setIsPlaying(nextPlay);
                  sendWSState({ isPlaying: nextPlay });
                }}
                className={`flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-xl font-black text-xs uppercase tracking-widest cursor-pointer transition-all ${
                  isPlaying 
                    ? "bg-red-600 hover:bg-red-500 text-white shadow-lg shadow-red-950/20" 
                    : "bg-emerald-600 hover:bg-emerald-500 text-white shadow-lg shadow-emerald-950/20"
                }`}
              >
                {isPlaying ? <Pause size={14} /> : <Play size={14} />}
                {isPlaying ? "PAUSE" : "PLAY"}
              </button>

              <button
                onClick={handleAutoSync}
                className="flex items-center justify-center gap-1.5 py-3 px-4 bg-zinc-900 border border-zinc-850 hover:border-zinc-700 text-zinc-300 font-black text-xs uppercase tracking-wider rounded-xl transition-all cursor-pointer"
                title="Match tempo instantly"
              >
                <Zap size={13} className="text-emerald-400" />
                SYNC
              </button>
            </div>

            {/* Scrolling Beatgrid Waveform Monitor */}
            <div className="relative bg-zinc-900 border border-zinc-850 p-1.5 rounded-xl">
              <canvas
                ref={canvasRef}
                width={600}
                height={70}
                className="w-full h-[70px] rounded border border-zinc-950 shadow-inner"
              />
              <div className={`absolute top-2.5 right-3.5 bg-zinc-950/90 px-2 py-0.5 rounded text-[8px] font-mono ${themeColorText}`}>
                SPEED: {state.bpm.toFixed(2)} BPM
              </div>
            </div>

            {/* Custom URL Load and Visibility */}
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <input
                  type="text"
                  placeholder="Paste YouTube Overrides URL..."
                  value={customUrl}
                  onChange={(e) => setCustomUrl(e.target.value)}
                  className="bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 w-full focus:outline-none focus:border-zinc-700"
                />
                <button
                  onClick={() => handleCustomUrlLoad(deck)}
                  className={`absolute right-2 top-2 ${themeColorText} hover:opacity-80 transition-colors`}
                >
                  <Search size={12} />
                </button>
              </div>
              <button
                onClick={() => setShowVideo(!showVideo)}
                className={`p-2 border rounded-lg transition-all ${
                  showVideo ? "border-zinc-700 text-zinc-300 bg-zinc-900" : "border-zinc-850 text-zinc-600 hover:bg-zinc-900"
                }`}
                title="Toggle visual video feed"
              >
                <Tv size={13} />
              </button>
            </div>
          </div>
        </div>

        {/* Crate Selection Loader */}
        <div className="bg-zinc-900/40 border border-zinc-850 p-4 rounded-xl space-y-2">
          <span className="text-[10px] text-zinc-500 font-bold uppercase tracking-wider">LOAD FROM LIVE TRACK CRATE</span>
          <div className="flex gap-2">
            <select
              onChange={(e) => {
                const tr = tracks.find(t => t.id === e.target.value);
                if (tr) {
                  loadTrackToDeck(tr, deck);
                  sendWSEvent("load_track_direct", { deck, trackId: tr.id });
                }
              }}
              value={state.track?.id || ""}
              className="bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-200 flex-1 focus:ring-1 focus:ring-zinc-700 focus:outline-none"
            >
              <option value="" disabled>Load Song to Deck {deck}...</option>
              {tracks.map(t => (
                <option key={t.id} value={t.id}>{t.title} ({t.bpm} BPM - {t.camelotKey})</option>
              ))}
            </select>
          </div>
        </div>

        {/* Structure map & cues rendering */}
        {renderTimeline(deck)}
      </div>
    );
  }

  function renderTactileSamplerScreen() {
    return (
      <div className="space-y-5 animate-fade-in bg-zinc-950 p-6 border border-zinc-850 rounded-2xl shadow-xl">
        <div className="flex justify-between items-center border-b border-zinc-850 pb-3">
          <div className="flex items-center gap-2">
            <Radio className="text-purple-400 animate-pulse" size={18} />
            <h3 className="text-sm font-black uppercase tracking-widest text-white">
              Tactile Studio Kaoss & Performance Sampler
            </h3>
          </div>
          <button
            onClick={triggerAutoGrab}
            className="flex items-center justify-center gap-1.5 py-1 px-3 bg-purple-500/10 hover:bg-purple-500/20 border border-purple-500/30 text-purple-400 text-3xs font-black uppercase rounded tracking-wider transition-all cursor-pointer"
          >
            <Sparkles size={11} className="text-purple-400" />
            Auto Grab Samples
          </button>
        </div>

        {/* Live Vector Touch Vector Pad XY Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
          <div className="lg:col-span-7 space-y-2">
            <span className="text-[10px] text-purple-400 font-bold uppercase tracking-wider block">
              Vector Coordinate Touchpad [XY SWEEP ENGINE]
            </span>
            <div 
              className="relative aspect-video max-h-[300px] w-full bg-zinc-950 rounded-xl overflow-hidden border border-purple-950/50 cursor-crosshair select-none shadow-2xl shadow-purple-950/5"
              onMouseDown={handleKaossPointerDown}
              onMouseMove={handleKaossPointerMove}
              onMouseUp={handleKaossPointerUp}
              onMouseLeave={handleKaossPointerUp}
              onTouchStart={handleKaossPointerDown}
              onTouchMove={handleKaossPointerMove}
              onTouchEnd={handleKaossPointerUp}
            >
              <canvas
                ref={kaossCanvasRef}
                width={500}
                height={260}
                className="w-full h-full"
              />

              {/* Floating Indicator details */}
              <div className="absolute top-3 left-3 bg-zinc-950/80 border border-zinc-850 px-2 py-0.5 rounded text-[8px] font-mono text-zinc-400">
                ACTIVE PAD: #{selectedKaossPadId} &mdash; {samplerPads[selectedKaossPadId - 1]?.synthType.toUpperCase()}
              </div>
            </div>
          </div>

          {/* Tactile Pads Grid */}
          <div className="lg:col-span-5 space-y-3">
            <span className="text-[10px] text-zinc-500 font-bold uppercase tracking-wider block">
              Select Pad to Assign Vector Focus
            </span>
            <div className="grid grid-cols-4 gap-2">
              {samplerPads.map((pad) => {
                const isSelected = selectedKaossPadId === pad.id;
                return (
                  <button
                    key={pad.id}
                    onClick={() => {
                      initAudioContext();
                      setSelectedKaossPadId(pad.id);
                      sendWSState({ kaoss: { padId: pad.id } });
                    }}
                    onDoubleClick={() => playSamplerSound(pad)}
                    className={`relative aspect-square rounded-xl p-2 flex flex-col justify-between text-left transition-all cursor-pointer ${
                      isSelected 
                        ? "bg-purple-600 border border-purple-400 text-white shadow-lg shadow-purple-900/30" 
                        : "bg-zinc-900 border border-zinc-850/80 text-zinc-400 hover:border-zinc-700 hover:bg-zinc-900/80"
                    }`}
                  >
                    <span className="text-[8px] font-bold font-mono">#{pad.id}</span>
                    <span className="text-[9px] font-black truncate max-w-[65px] leading-tight block">
                      {pad.isAssigned ? pad.name : "EMPTY"}
                    </span>
                    <div className="flex justify-between items-center w-full">
                      <span className="text-[7px] opacity-60 font-mono tracking-wide">
                        {pad.synthType.toUpperCase()}
                      </span>
                      {pad.isPlaying && (
                        <span className="w-1.5 h-1.5 rounded-full bg-white animate-ping"></span>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>

            {/* Quick Trigger Buttons */}
            <div className="flex gap-2">
              <button
                onClick={() => {
                  const pad = samplerPads[selectedKaossPadId - 1];
                  if (pad) playSamplerSound(pad);
                }}
                className="flex-1 py-2.5 px-4 bg-purple-500 hover:bg-purple-400 text-zinc-950 text-xs font-black uppercase tracking-wider rounded-xl transition-all cursor-pointer"
              >
                Trigger Focused Pad
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  function renderTactileMixerScreen() {
    return (
      <div className="space-y-5 animate-fade-in bg-zinc-950 p-6 border border-zinc-850 rounded-2xl shadow-xl">
        <div className="flex justify-between items-center border-b border-zinc-850 pb-3">
          <div className="flex items-center gap-2">
            <Sliders className="text-cyan-400" size={18} />
            <h3 className="text-sm font-black uppercase tracking-widest text-white">
              Tactile Master Mixer & Automatchic Panel
            </h3>
          </div>
          <span className="bg-zinc-900 border border-zinc-800 text-[9px] font-mono font-bold text-zinc-400 px-2 py-0.5 rounded">
            CROSSFADER: {autoMixCrossfader}%
          </span>
        </div>

        {/* Mixer Faders & EQ Knobs */}
        <div className="grid grid-cols-1 md:grid-cols-12 gap-5 bg-zinc-900/35 p-4 rounded-xl border border-zinc-850/60">
          {/* Deck A Channel Strip */}
          <div className="md:col-span-4 bg-zinc-900/60 p-3 rounded-lg border border-zinc-850 flex flex-col items-center space-y-4">
            <span className="text-[9px] font-black tracking-widest text-cyan-400 uppercase">CH A FADER</span>
            <div className="h-40 flex items-center justify-center">
              {/* Vertical Slider Simulation */}
              <input
                type="range"
                min="0"
                max="0.8"
                step="0.05"
                value={audioVolume}
                onChange={(e) => {
                  const val = parseFloat(e.target.value);
                  setAudioVolume(val);
                  sendWSState({ audioVolume: val });
                }}
                style={{ transform: "rotate(-90deg)", width: "120px" }}
                className="accent-cyan-500 bg-zinc-950 h-1.5 rounded cursor-pointer"
              />
            </div>
            <span className="text-xs font-mono text-zinc-400">VOL: {Math.round(audioVolume * 125)}%</span>
          </div>

          {/* Master Crossfader Panel */}
          <div className="md:col-span-4 flex flex-col justify-center items-center space-y-5 px-4 py-4 md:py-0 border-t md:border-t-0 md:border-l md:border-r border-zinc-850">
            <span className="text-[10px] font-black tracking-widest text-zinc-500 uppercase">CROSSFADER MATRIX</span>
            <div className="w-full flex items-center gap-3">
              <span className="text-[10px] font-bold text-cyan-400">A</span>
              <input
                type="range"
                min="-100"
                max="100"
                step="5"
                value={autoMixCrossfader}
                onChange={(e) => {
                  const val = parseInt(e.target.value);
                  setAutoMixCrossfader(val);
                  sendWSState({ crossfader: val });
                }}
                className="flex-1 accent-purple-500 h-1.5 bg-zinc-950 rounded cursor-pointer"
              />
              <span className="text-[10px] font-bold text-amber-500">B</span>
            </div>
            <p className="text-4xs text-zinc-500 text-center uppercase tracking-wider font-mono">
              Balances audio signal weights between primary Deck A and secondary Deck B
            </p>
          </div>

          {/* Deck B Channel Strip */}
          <div className="md:col-span-4 bg-zinc-900/60 p-3 rounded-lg border border-zinc-850 flex flex-col items-center space-y-4">
            <span className="text-[9px] font-black tracking-widest text-amber-500 uppercase">CH B FADER</span>
            <div className="h-40 flex items-center justify-center">
              <input
                type="range"
                min="0"
                max="0.8"
                step="0.05"
                value={audioVolume}
                onChange={(e) => {
                  const val = parseFloat(e.target.value);
                  setAudioVolume(val);
                  sendWSState({ audioVolume: val });
                }}
                style={{ transform: "rotate(-90deg)", width: "120px" }}
                className="accent-amber-500 bg-zinc-950 h-1.5 rounded cursor-pointer"
              />
            </div>
            <span className="text-xs font-mono text-zinc-400">VOL: {Math.round(audioVolume * 125)}%</span>
          </div>
        </div>

        {/* Automix Playlist & Stage tracking */}
        <div className="bg-zinc-900/35 border border-zinc-850 p-4 rounded-xl space-y-4">
          <div className="flex justify-between items-center border-b border-zinc-800 pb-2">
            <span className="text-[10px] font-bold text-zinc-400 uppercase tracking-widest block">
              Automatchic Mix Automation sequence
            </span>
            <div className="flex gap-2">
              {!isAutoMixing ? (
                <button
                  onClick={() => {
                    startAutomatchicMix();
                    sendWSEvent("automix_action", { action: "start" });
                  }}
                  className="py-1 px-3 bg-amber-500 hover:bg-amber-400 text-zinc-950 text-3xs font-black uppercase rounded tracking-wider transition-all cursor-pointer"
                >
                  Start Automix
                </button>
              ) : (
                <button
                  onClick={() => {
                    stopAutomatchicMix();
                    sendWSEvent("automix_action", { action: "stop" });
                  }}
                  className="py-1 px-3 bg-red-600 hover:bg-red-500 text-white text-3xs font-black uppercase rounded tracking-wider transition-all cursor-pointer"
                >
                  Stop Automix
                </button>
              )}
            </div>
          </div>

          {/* Active Status metrics */}
          {isAutoMixing ? (
            <div className="space-y-3.5">
              <div className="grid grid-cols-2 gap-3 text-3xs">
                <div className="bg-zinc-950/60 p-2.5 rounded border border-zinc-850">
                  <span className="text-zinc-500 uppercase font-bold block">Current Sequence Playlist Item</span>
                  <span className="text-zinc-200 font-bold font-mono">
                    #{autoMixCurrentIndex + 1} &mdash; {autoMixPlaylist[autoMixCurrentIndex]?.title || "N/A"}
                  </span>
                </div>
                <div className="bg-zinc-950/60 p-2.5 rounded border border-zinc-850">
                  <span className="text-zinc-500 uppercase font-bold block">Blend transition countdown</span>
                  <span className="text-amber-400 font-black font-mono">
                    {autoMixTimeRemaining}s / {autoMixDuration}s remaining
                  </span>
                </div>
              </div>

              {/* Countdown progress visual slider */}
              <div className="w-full bg-zinc-950 rounded-full h-1.5 overflow-hidden">
                <div 
                  className="bg-amber-500 h-full transition-all duration-1000" 
                  style={{ width: `${(autoMixTimeRemaining / autoMixDuration) * 100}%` }}
                />
              </div>

              <div className="bg-amber-950/20 border border-amber-900/30 p-2.5 rounded-lg text-3xs font-mono text-amber-300">
                <strong className="text-amber-400">SEQUENCE LEVEL STATUS:</strong> {autoMixStatus}
              </div>
            </div>
          ) : (
            <div className="text-center py-6 text-zinc-500 text-2xs">
              Mixer Automation is currently offline. Trigger above to load greedy compatible tracks sequence.
            </div>
          )}
        </div>
      </div>
    );
  }

  function renderApiHub() {
    return (
      <div className="mt-6 border-t border-zinc-850 pt-5 space-y-3">
        <div 
          onClick={() => setIsApiHubExpanded(!isApiHubExpanded)}
          className="flex items-center justify-between bg-zinc-900/40 hover:bg-zinc-900/70 border border-zinc-850 p-4 rounded-xl cursor-pointer transition-colors"
        >
          <div className="flex items-center gap-2.5">
            <Sliders size={15} className="text-cyan-400" />
            <div>
              <h4 className="text-xs font-bold text-zinc-200 uppercase tracking-wider font-sans">
                Developer API Control Hub & Telemetry Explorer
              </h4>
              <p className="text-4xs text-zinc-500">
                Inspect live state parameters, trigger actions programmatically, or copy command signatures to control the mixer externally.
              </p>
            </div>
          </div>
          <div className="text-[10px] text-zinc-400 font-mono flex items-center gap-1.5">
            <span className="bg-zinc-950 border border-zinc-850 px-2 py-0.5 rounded text-[8px] font-bold text-cyan-300">
              API EXPOSED: window.proMixingConsoleApi
            </span>
            <span>{isApiHubExpanded ? "▲ Hide" : "▼ Show"}</span>
          </div>
        </div>

        {isApiHubExpanded && (
          <div className="grid grid-cols-1 md:grid-cols-12 gap-5 animate-fade-in bg-zinc-950/20 p-4 border border-zinc-850 rounded-xl">
            {/* Live Controller interactive buttons (6 cols) */}
            <div className="md:col-span-6 space-y-3.5">
              <span className="text-[9px] font-bold text-zinc-400 tracking-wider uppercase font-sans block border-b border-zinc-850/60 pb-1.5">
                Tactile Live API Explorer
              </span>
              <div className="grid grid-cols-2 gap-2 text-[10px] font-mono">
                <button
                  onClick={() => {
                    initAudioContext();
                    setIsPlaying(prev => !prev);
                    addApiLog(`window.proMixingConsoleApi.togglePlay() -> Playing: ${!isPlaying}`);
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Toggle Playback
                </button>
                <button
                  onClick={() => {
                    handleAutoSync();
                    addApiLog(`window.proMixingConsoleApi.syncBeats() -> Tempos matched`);
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Sync Beats (Align)
                </button>
                <button
                  onClick={() => {
                    if (deckA.track) {
                      setDeckACues(prev => prev.map((v, i) => i === 0 ? Math.round(timeA) : v));
                      addApiLog(`window.proMixingConsoleApi.setCuePoint("A", 1, ${Math.round(timeA)})`);
                    }
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Set Cue 1 (Deck A)
                </button>
                <button
                  onClick={() => {
                    if (deckACues[0] !== null) {
                      handleSeek("A", deckACues[0] as number);
                      addApiLog(`window.proMixingConsoleApi.triggerCue("A", 1) -> Seeked to ${deckACues[0]}s`);
                    } else {
                      addApiLog(`window.proMixingConsoleApi.triggerCue("A", 1) -> Failed (Cue unset)`);
                    }
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Trigger Cue 1 (Deck A)
                </button>
                <button
                  onClick={() => {
                    triggerAutoGrab();
                    addApiLog(`window.proMixingConsoleApi.autoGrabAtmospheres() -> Pads filled`);
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Auto Grab Stems
                </button>
                <button
                  onClick={() => {
                    const pad = samplerPads[4];
                    if (pad) {
                      playSamplerSound(pad);
                      addApiLog(`window.proMixingConsoleApi.triggerPad(5) -> Played: ${pad.name}`);
                    }
                  }}
                  className="py-1.5 px-3 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-zinc-300 rounded text-left transition-colors cursor-pointer"
                >
                  ⚡ Trigger Pad 5
                </button>
              </div>

              {/* Developer Logs Console */}
              <div className="space-y-1">
                <span className="block text-[8px] uppercase tracking-widest text-zinc-500 font-mono">Live API Call History Logs</span>
                <div className="bg-black/80 border border-zinc-900 p-2.5 rounded-lg h-[95px] overflow-y-auto text-[9.5px] font-mono text-cyan-400 space-y-1">
                  {apiConsoleLogs.map((log, i) => (
                    <div key={i} className="truncate border-l-2 border-cyan-800/40 pl-1.5">{log}</div>
                  ))}
                </div>
              </div>
            </div>

            {/* API Code snippet / console instructions (6 cols) */}
            <div className="md:col-span-6 space-y-3 flex flex-col justify-between">
              <div className="space-y-1.5">
                <span className="text-[9px] font-bold text-zinc-400 tracking-wider uppercase font-sans block border-b border-zinc-850/60 pb-1.5">
                  Command Line JavaScript Reference
                </span>
                <p className="text-4xs text-zinc-400 leading-normal">
                  Open your browser's Developer Console (F12 or Option+Cmd+I) and enter any command to execute custom scripts on the mixer:
                </p>
                <pre className="bg-zinc-900 border border-zinc-850 p-2.5 rounded text-[8.5px] font-mono text-zinc-300 whitespace-pre-wrap select-all leading-normal">
{`// Read full console parameters JSON
const state = window.proMixingConsoleApi.getConsoleState();
console.log("Current BPM A:", state.deckA.bpm);

// Set Deck A speed pitch fader to +2.5%
window.proMixingConsoleApi.setPitch("A", 2.5);

// Set Cue point 2 on Deck B to current playing time
window.proMixingConsoleApi.setCuePoint("B", 2, 45);

// Jump directly to Cue 2 on Deck B
window.proMixingConsoleApi.triggerCue("B", 2);

// Play vocal slice sampler Pad 4
window.proMixingConsoleApi.triggerPad(4);`}
                </pre>
              </div>

              <div className="bg-cyan-950/15 border border-cyan-900/30 p-2.5 rounded-lg text-4xs leading-normal text-cyan-400/85 font-mono">
                <span className="font-extrabold block text-[9.5px] text-cyan-300 mb-0.5">ℹ️ BROWSER CONSOLE INTEGRATION:</span>
                Because the API is registered directly to \`window.proMixingConsoleApi\`, it is globally available. You can write custom automation scripts, hotkey listeners, or telemetry monitors dynamically from the developer console!
              </div>
            </div>
          </div>
        )}
      </div>
    );
  };
}
