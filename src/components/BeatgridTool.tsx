import React, { useState, useEffect, useRef } from "react";
import { Play, Pause, RotateCcw, Volume2, VolumeX, Zap, ArrowLeft, ArrowRight, HelpCircle, Youtube, Tv, Link2, Search } from "lucide-react";
import { Track } from "../types";

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
}

interface BeatgridToolProps {
  tracks: Track[];
  initialDeckATrack?: Track | null;
  initialDeckBTrack?: Track | null;
}

export default function BeatgridTool({ tracks, initialDeckATrack, initialDeckBTrack }: BeatgridToolProps) {
  // Setup standard state for both decks
  const [deckA, setDeckA] = useState<DeckState>({
    track: initialDeckATrack || null,
    baseBpm: initialDeckATrack?.bpm || 120,
    pitch: 0,
    bpm: initialDeckATrack?.bpm || 120,
    phaseOffset: 0,
    isMuted: true,
  });

  const [deckB, setDeckB] = useState<DeckState>({
    track: initialDeckBTrack || null,
    baseBpm: initialDeckBTrack?.bpm || 120,
    pitch: 0,
    bpm: initialDeckBTrack?.bpm || 120,
    phaseOffset: 0.15, // slightly off phase by default for training
    isMuted: true,
  });

  const [isPlaying, setIsPlaying] = useState(false);
  const [audioVolume, setAudioVolume] = useState(0.4);
  const [isAudioContextInitialized, setIsAudioContextInitialized] = useState(false);
  const [showHelp, setShowHelp] = useState(false);

  // Sync parameters
  const [alignmentScore, setAlignmentScore] = useState(100);

  // Audio Context Ref
  const audioCtxRef = useRef<AudioContext | null>(null);

  // Canvas Refs for animation
  const canvasARef = useRef<HTMLCanvasElement | null>(null);
  const canvasBRef = useRef<HTMLCanvasElement | null>(null);

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

  // Sync volume / mute controls to YouTube Players
  useEffect(() => {
    if (playerAReady && playerARef.current && typeof playerARef.current.setVolume === "function") {
      try { playerARef.current.setVolume(deckA.isMuted ? 0 : audioVolume * 100); } catch (err) {}
    }
  }, [audioVolume, deckA.isMuted, playerAReady]);

  useEffect(() => {
    if (playerBReady && playerBRef.current && typeof playerBRef.current.setVolume === "function") {
      try { playerBRef.current.setVolume(deckB.isMuted ? 0 : audioVolume * 100); } catch (err) {}
    }
  }, [audioVolume, deckB.isMuted, playerBReady]);

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

  return (
    <div id="beatgrid-tool-panel" className="bg-zinc-950 border border-zinc-800 rounded-xl p-5 shadow-2xl space-y-6">
      {/* Header and Sync Display */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 pb-4 border-b border-zinc-800">
        <div>
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></span>
            <h3 className="text-lg font-bold text-zinc-100 uppercase tracking-wider">Manual Beatgrid Alignment Console</h3>
          </div>
          <p className="text-xs text-zinc-400">Align scrolling beat lines visually. Lock tempos & phases manually or use automated DJ Sync.</p>
        </div>
        
        <div className="flex items-center gap-3 w-full md:w-auto">
          {/* Sync Alignment Score Meter */}
          <div className="flex-1 md:flex-initial bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-center min-w-[140px]">
            <div className="text-2xs uppercase tracking-widest text-zinc-500">Grid Sync Accuracy</div>
            <div className={`text-xl font-black font-mono tracking-tight ${
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
            onClick={() => setShowHelp(!showHelp)}
            className="p-2 border border-zinc-800 rounded-lg text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900 transition-all cursor-pointer"
            title="Help & Guidelines"
            id="help-btn"
          >
            <HelpCircle size={18} />
          </button>
        </div>
      </div>

      {/* Embedded Help Overlay */}
      {showHelp && (
        <div className="bg-zinc-900/90 border border-zinc-800 p-4 rounded-lg text-xs leading-relaxed text-zinc-300 space-y-2">
          <p className="font-bold text-zinc-100">How to use the Beatgrid Matcher:</p>
          <ul className="list-disc pl-4 space-y-1">
            <li><strong>Objective:</strong> Align the vertical beat lines of both Deck A and Deck B with each other as they scroll. They should cross the red center playhead simultaneously.</li>
            <li><strong>Manual Sync:</strong> Adjust the <span className="text-cyan-400 font-mono">BPM (Pitch Slider)</span> of Deck B until it matches Deck A. Use the <span className="text-amber-400 font-mono">Nudge ◀ / ▶</span> buttons to nudge Deck B forward or backward until the lines align perfectly.</li>
            <li><strong>Sync Button:</strong> Click <span className="text-emerald-400 font-bold">Sync Beats</span> to lock BPM and Phase alignment automatically (replicates controller hardware).</li>
            <li><strong>Beep Synth:</strong> Toggle the <span className="font-bold text-zinc-100">speaker icon</span> on each deck to activate audio block ticks on the beat. Train your ears!</li>
          </ul>
        </div>
      )}

      {/* Main Track Loading Row */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Deck A Loader */}
        <div className="bg-zinc-900 border border-zinc-850 p-3 rounded-lg flex flex-col justify-between">
          <div className="flex justify-between items-start gap-2 mb-2">
            <div>
              <span className="text-xs font-semibold text-cyan-400 tracking-wider">DECK A</span>
              <h4 className="text-sm font-bold text-white truncate max-w-[200px]">
                {deckA.track ? `${deckA.track.title}` : "No Track Loaded"}
              </h4>
              <p className="text-xs text-zinc-400 truncate max-w-[200px]">
                {deckA.track ? deckA.track.artist : "Select a track to load"}
              </p>
            </div>
            {deckA.track && (
              <span className="bg-cyan-950 text-cyan-400 border border-cyan-800 text-3xs font-mono px-2 py-0.5 rounded font-black">
                {deckA.bpm} BPM | {deckA.track.camelotKey}
              </span>
            )}
          </div>
          
          <div className="flex gap-2 items-center mt-3">
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
        <div className="bg-zinc-900 border border-zinc-850 p-3 rounded-lg flex flex-col justify-between">
          <div className="flex justify-between items-start gap-2 mb-2">
            <div>
              <span className="text-xs font-semibold text-amber-500 tracking-wider">DECK B</span>
              <h4 className="text-sm font-bold text-white truncate max-w-[200px]">
                {deckB.track ? `${deckB.track.title}` : "No Track Loaded"}
              </h4>
              <p className="text-xs text-zinc-400 truncate max-w-[200px]">
                {deckB.track ? deckB.track.artist : "Select a track to load"}
              </p>
            </div>
            {deckB.track && (
              <span className="bg-amber-950 text-amber-400 border border-amber-800 text-3xs font-mono px-2 py-0.5 rounded font-black">
                {deckB.bpm} BPM | {deckB.track.camelotKey}
              </span>
            )}
          </div>
          
          <div className="flex gap-2 items-center mt-3">
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

      {/* Interactive Visual Waveform scrolling display */}
      <div className="space-y-6 bg-zinc-900 border border-zinc-850 p-4 rounded-xl">
        {/* DECK A VISUAL GROUP */}
        <div className="space-y-3">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 border-b border-zinc-850 pb-2">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse"></span>
              <span className="text-2xs font-bold uppercase tracking-wider text-zinc-300">Deck A Video Monitor</span>
              {isSearchingA && (
                <span className="text-4xs text-zinc-500 animate-pulse">(Connecting Live Stream...)</span>
              )}
            </div>
            <div className="flex items-center gap-2 w-full sm:w-auto">
              <div className="relative flex-1 sm:flex-initial">
                <input
                  type="text"
                  placeholder="Override YouTube Link/ID..."
                  value={customUrlA}
                  onChange={(e) => setCustomUrlA(e.target.value)}
                  className="bg-zinc-950 border border-zinc-800 rounded pl-2 pr-6 py-0.5 text-4xs text-zinc-300 w-full sm:w-[180px] focus:outline-none focus:border-cyan-500"
                />
                <button
                  onClick={() => handleCustomUrlLoad("A")}
                  className="absolute right-1.5 top-1 text-cyan-400 hover:text-cyan-300 transition-colors"
                  title="Connect video"
                >
                  <Search size={10} />
                </button>
              </div>
              <button
                onClick={() => setShowVideoA(!showVideoA)}
                className={`p-1 border rounded text-4xs font-bold transition-all ${
                  showVideoA ? "border-cyan-800 text-cyan-400 bg-cyan-950/20" : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                }`}
                title={showVideoA ? "Hide Video Monitor" : "Show Video Monitor"}
              >
                <Tv size={12} />
              </button>
            </div>
          </div>

          {showVideoA && youtubeIdA && (
            <div className="relative aspect-video max-h-[180px] sm:max-h-[220px] w-full mx-auto bg-black rounded-lg overflow-hidden border border-cyan-950">
              <div id="youtube-player-deck-a" className="w-full h-full"></div>
            </div>
          )}

          {/* DECK A VISUALIZER CANVAS */}
          <div className="relative">
            <canvas
              ref={canvasARef}
              width={800}
              height={90}
              className="w-full h-[90px] rounded border border-zinc-800"
            />
            <div className="absolute top-2 right-3 flex items-center gap-2 bg-zinc-950/80 px-2 py-0.5 rounded text-4xs font-mono text-cyan-400">
              <span>SPEED: {deckA.bpm} BPM</span>
              {deckA.pitch !== 0 && <span>({deckA.pitch > 0 ? "+" : ""}{deckA.pitch.toFixed(1)}%)</span>}
            </div>
          </div>
        </div>

        {/* DECK B VISUAL GROUP */}
        <div className="space-y-3">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 border-b border-zinc-850 pb-2">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse"></span>
              <span className="text-2xs font-bold uppercase tracking-wider text-zinc-300">Deck B Video Monitor</span>
              {isSearchingB && (
                <span className="text-4xs text-zinc-500 animate-pulse">(Connecting Live Stream...)</span>
              )}
            </div>
            <div className="flex items-center gap-2 w-full sm:w-auto">
              <div className="relative flex-1 sm:flex-initial">
                <input
                  type="text"
                  placeholder="Override YouTube Link/ID..."
                  value={customUrlB}
                  onChange={(e) => setCustomUrlB(e.target.value)}
                  className="bg-zinc-950 border border-zinc-800 rounded pl-2 pr-6 py-0.5 text-4xs text-zinc-300 w-full sm:w-[180px] focus:outline-none focus:border-amber-500"
                />
                <button
                  onClick={() => handleCustomUrlLoad("B")}
                  className="absolute right-1.5 top-1 text-amber-400 hover:text-amber-300 transition-colors"
                  title="Connect video"
                >
                  <Search size={10} />
                </button>
              </div>
              <button
                onClick={() => setShowVideoB(!showVideoB)}
                className={`p-1 border rounded text-4xs font-bold transition-all ${
                  showVideoB ? "border-amber-800 text-amber-400 bg-amber-950/20" : "border-zinc-800 text-zinc-500 hover:bg-zinc-850"
                }`}
                title={showVideoB ? "Hide Video Monitor" : "Show Video Monitor"}
              >
                <Tv size={12} />
              </button>
            </div>
          </div>

          {showVideoB && youtubeIdB && (
            <div className="relative aspect-video max-h-[180px] sm:max-h-[220px] w-full mx-auto bg-black rounded-lg overflow-hidden border border-amber-950">
              <div id="youtube-player-deck-b" className="w-full h-full"></div>
            </div>
          )}

          {/* DECK B VISUALIZER CANVAS */}
          <div className="relative">
            <canvas
              ref={canvasBRef}
              width={800}
              height={90}
              className="w-full h-[90px] rounded border border-zinc-800"
            />
            <div className="absolute top-2 right-3 flex items-center gap-2 bg-zinc-950/80 px-2 py-0.5 rounded text-4xs font-mono text-amber-400">
              <span>SPEED: {deckB.bpm} BPM</span>
              {deckB.pitch !== 0 && <span>({deckB.pitch > 0 ? "+" : ""}{deckB.pitch.toFixed(1)}%)</span>}
            </div>
          </div>
        </div>
      </div>

      {/* Control Hardware Panel: Play/Pause, Pitch Faders, Nudge Controls */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 bg-zinc-900/60 p-4 border border-zinc-850 rounded-xl">
        {/* Pitch Fader A */}
        <div className="flex flex-col justify-center items-center p-3 border-b md:border-b-0 md:border-r border-zinc-850 pb-5 md:pb-3">
          <span className="text-2xs text-cyan-400 font-bold tracking-widest uppercase mb-1">DECK A Pitch</span>
          <div className="flex items-center gap-4 w-full px-4">
            <span className="text-3xs font-mono text-zinc-500">-8%</span>
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
              className="flex-1 accent-cyan-500 h-1 bg-zinc-800 rounded-lg appearance-none cursor-pointer"
              id="deck-a-pitch-range"
            />
            <span className="text-3xs font-mono text-zinc-500">+8%</span>
          </div>
          <div className="text-xs font-mono text-zinc-300 mt-2">
            BPM: <strong className="text-cyan-400">{deckA.bpm.toFixed(2)}</strong>
            {deckA.pitch !== 0 && <span className="text-zinc-500 text-3xs ml-1">({deckA.pitch > 0 ? "+" : ""}{deckA.pitch.toFixed(2)}%)</span>}
          </div>
          <button
            onClick={() => setDeckA(prev => ({ ...prev, pitch: 0 }))}
            className="text-3xs uppercase tracking-widest text-zinc-500 hover:text-zinc-300 transition-colors mt-2 underline cursor-pointer"
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
              className={`p-4 rounded-full cursor-pointer shadow-lg transition-all transform hover:scale-105 active:scale-95 ${
                isPlaying 
                  ? "bg-red-600 hover:bg-red-500 text-white" 
                  : "bg-emerald-600 hover:bg-emerald-500 text-white"
              }`}
              title={isPlaying ? "Pause Engines" : "Start Beatgrid Roll"}
              id="play-pause-btn"
            >
              {isPlaying ? <Pause size={24} /> : <Play size={24} />}
            </button>

            {/* Reset Positions */}
            <button
              onClick={handleReset}
              className="p-3 bg-zinc-800 hover:bg-zinc-700 text-zinc-300 hover:text-white rounded-full transition-all cursor-pointer border border-zinc-700"
              title="Reset Alignment & Offsets"
              id="reset-align-btn"
            >
              <RotateCcw size={18} />
            </button>
          </div>

          {/* Sync & Audio Volume Slider */}
          <div className="flex flex-col items-center gap-2 w-full px-6">
            <button
              onClick={handleAutoSync}
              className="flex items-center justify-center gap-2 w-full py-2 px-4 bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 text-xs font-bold uppercase rounded-lg tracking-widest transition-all cursor-pointer"
              title="Instantly Match Deck B to Deck A BPM and Phase alignment"
              id="sync-beats-btn"
            >
              <Zap size={14} className="fill-emerald-400" />
              Sync Beats (Auto Align)
            </button>

            {/* Audio volume slider */}
            <div className="flex items-center gap-2 w-full mt-2 justify-center">
              <Volume2 size={12} className="text-zinc-500" />
              <input
                type="range"
                min="0"
                max="0.8"
                step="0.05"
                value={audioVolume}
                onChange={(e) => setAudioVolume(parseFloat(e.target.value))}
                className="w-24 accent-emerald-500 h-1 bg-zinc-800 rounded-lg appearance-none cursor-pointer"
                title="Tone Beat Synths Volume"
              />
              <span className="text-3xs font-mono text-zinc-500">Vol: {Math.round(audioVolume * 125)}%</span>
            </div>
          </div>
        </div>

        {/* Pitch Fader B & Manual Nudge */}
        <div className="flex flex-col justify-center items-center p-3 border-t md:border-t-0 md:border-l border-zinc-850 pt-5 md:pt-3">
          <span className="text-2xs text-amber-400 font-bold tracking-widest uppercase mb-1">DECK B Pitch</span>
          <div className="flex items-center gap-4 w-full px-4">
            <span className="text-3xs font-mono text-zinc-500">-8%</span>
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
              className="flex-1 accent-amber-500 h-1 bg-zinc-800 rounded-lg appearance-none cursor-pointer"
              id="deck-b-pitch-range"
            />
            <span className="text-3xs font-mono text-zinc-500">+8%</span>
          </div>
          
          <div className="text-xs font-mono text-zinc-300 mt-2">
            BPM: <strong className="text-amber-400">{deckB.bpm.toFixed(2)}</strong>
            {deckB.pitch !== 0 && <span className="text-zinc-500 text-3xs ml-1">({deckB.pitch > 0 ? "+" : ""}{deckB.pitch.toFixed(2)}%)</span>}
          </div>

          {/* Nudge Controls */}
          <div className="flex items-center gap-2 mt-3 w-full justify-center">
            <button
              onClick={() => nudgeDeckB("backward")}
              className="flex items-center gap-1 py-1 px-3 bg-zinc-800 hover:bg-zinc-700 border border-zinc-750 hover:border-zinc-600 rounded text-3xs text-amber-400 font-bold uppercase transition-all cursor-pointer"
              title="Slow down phase of Deck B temporarily"
              id="nudge-back-btn"
            >
              <ArrowLeft size={10} />
              Nudge -
            </button>
            <button
              onClick={() => nudgeDeckB("forward")}
              className="flex items-center gap-1 py-1 px-3 bg-zinc-800 hover:bg-zinc-700 border border-zinc-750 hover:border-zinc-600 rounded text-3xs text-amber-400 font-bold uppercase transition-all cursor-pointer"
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
  );
}
