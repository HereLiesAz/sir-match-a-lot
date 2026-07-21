import React, { useState, useEffect, useRef } from "react";
import { 
  Orbit, RotateCcw, Volume2, ShieldAlert, Sliders, Hand, HelpCircle, 
  Disc, SlidersHorizontal, Settings2, Sparkles, Activity, Play, Pause, Square
} from "lucide-react";
import { Track } from "../types";

interface DeckState {
  track: Track | null;
  baseBpm: number;
  pitch: number;
  bpm: number;
  phaseOffset: number;
  isMuted: boolean;
  autoStretch: boolean;
  transposeOffset: number;
}

interface RadialControllerProps {
  tracks: Track[];
  deckA: DeckState;
  deckB: DeckState;
  setDeckA: React.Dispatch<React.SetStateAction<DeckState>>;
  setDeckB: React.Dispatch<React.SetStateAction<DeckState>>;
  audioVolume: number;
  setAudioVolume: (vol: number) => void;
  autoMixCrossfader: number;
  setAutoMixCrossfader: (val: number) => void;
  isPlaying: boolean;
  setIsPlaying: (playing: boolean) => void;
  timeA: number;
  timeB: number;
  durationA: number;
  durationB: number;
  handleSeek: (deck: "A" | "B", timeSeconds: number) => void;
  setFeedbackMsg: (msg: string) => void;
}

export default function RadialController({
  tracks,
  deckA,
  deckB,
  setDeckA,
  setDeckB,
  audioVolume,
  setAudioVolume,
  autoMixCrossfader,
  setAutoMixCrossfader,
  isPlaying,
  setIsPlaying,
  timeA,
  timeB,
  durationA,
  durationB,
  handleSeek,
  setFeedbackMsg
}: RadialControllerProps) {
  // Active Deck Focus for single deck actions (A or B)
  const [activeDeckFocus, setActiveDeckFocus] = useState<"A" | "B">("A");

  // Simulated EQ states (Bass / Treble balance) for both decks
  const [eqBassA, setEqBassA] = useState<number>(0); // -100 to 100
  const [eqTrebleA, setEqTrebleA] = useState<number>(0); // -100 to 100
  const [eqBassB, setEqBassB] = useState<number>(0);
  const [eqTrebleB, setEqTrebleB] = useState<number>(0);

  // Global visual rotation of the main wheel
  const [wheelRotation, setWheelRotation] = useState<number>(0); // in radians

  // Desktop Simulator Settings
  const [fingerMode, setFingerMode] = useState<1 | 2 | 3>(1);
  const [isSimulating, setIsSimulating] = useState<boolean>(false);
  const [lastMousePos, setLastMousePos] = useState<{ x: number; y: number } | null>(null);

  // Touch event coordinates tracking
  const touchStartRef = useRef<{ x: number; y: number }[]>([]);
  const touchStartDistRef = useRef<number>(0);
  const touchStartAngleRef = useRef<number>(0);
  const touchStartCrossfaderRef = useRef<number>(0);
  const touchStartPitchRef = useRef<number>(0);
  const touchStartVolumeRef = useRef<number>(0);
  const touchStartWheelRotationRef = useRef<number>(0);

  // DOM elements references
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [dimensions, setDimensions] = useState({ width: 450, height: 450 });

  // Handle container resizing
  useEffect(() => {
    if (!containerRef.current) return;
    const observer = new ResizeObserver((entries) => {
      for (let entry of entries) {
        const d = Math.min(entry.contentRect.width, 500);
        setDimensions({ width: d || 450, height: d || 450 });
      }
    });
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, []);

  // Generate deterministic beautiful waveforms for each track
  const getProceduralWaveform = (trackId: string | undefined, count: number): number[] => {
    if (!trackId) {
      // Return a standard pulsing beatwave if no track is loaded
      return Array.from({ length: count }, (_, i) => 25 + Math.sin(i * 0.15) * 12 + Math.cos(i * 0.45) * 6);
    }
    // Generate deterministic values using a simple hash of the track ID
    let hash = 0;
    for (let i = 0; i < trackId.length; i++) {
      hash = trackId.charCodeAt(i) + ((hash << 5) - hash);
    }
    const peaks: number[] = [];
    for (let i = 0; i < count; i++) {
      const seed1 = Math.sin(i * 0.2 + hash * 0.05);
      const seed2 = Math.cos(i * 0.6 - hash * 0.12);
      const seed3 = Math.sin(i * 1.5 + hash * 0.3);
      // Rhythmic spike pattern simulating beats
      const beatSpike = i % 8 === 0 ? 18 : 0;
      const val = 18 + Math.abs(seed1 * 15 + seed2 * 8 + seed3 * 4) + beatSpike;
      peaks.push(val);
    }
    return peaks;
  };

  // Color mappings based on Energy level
  const getEnergyColor = (energy: number, opacity: number = 1) => {
    if (energy >= 8) {
      return `rgba(244, 63, 94, ${opacity})`; // Neon Rose/Red (High Energy)
    } else if (energy >= 5) {
      return `rgba(168, 85, 247, ${opacity})`; // Electric Purple/Indigo (Mid Energy)
    } else {
      return `rgba(6, 182, 212, ${opacity})`; // Neon Cyan/Teal (Low/Smooth Energy)
    }
  };

  // Main Canvas Rendering Loop
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animId: number;

    const render = () => {
      // Clear canvas with deep space neutral black
      ctx.fillStyle = "#09090b";
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      const cx = canvas.width / 2;
      const cy = canvas.height / 2;
      const baseRadius = Math.min(cx, cy) * 0.48;

      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(wheelRotation);

      // --- DECK A: Outer Concentric Ring ---
      const waveCountA = 120;
      const peaksA = getProceduralWaveform(deckA.track?.id, waveCountA);
      const energyA = deckA.track?.energyLevel || 5;
      const colorA = getEnergyColor(energyA);
      const rA = baseRadius * 1.35;

      ctx.beginPath();
      ctx.arc(0, 0, rA, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(63, 63, 70, 0.3)";
      ctx.lineWidth = 1.5;
      ctx.stroke();

      for (let i = 0; i < waveCountA; i++) {
        const angle = (i / waveCountA) * 2 * Math.PI;
        // Adjust radial bar size by EQ state (bass swells low frequencies, treble swells high-frequency spikes)
        let modifier = 1.0;
        if (i % 3 === 0) modifier += eqBassA * 0.0035;
        if (i % 3 !== 0) modifier += eqTrebleA * 0.0035;

        const peakHeight = Math.max(2, peaksA[i] * modifier);
        const startX = Math.cos(angle) * rA;
        const startY = Math.sin(angle) * rA;
        const endX = Math.cos(angle) * (rA + peakHeight * 0.7);
        const endY = Math.sin(angle) * (rA + peakHeight * 0.7);

        ctx.beginPath();
        ctx.moveTo(startX, startY);
        ctx.lineTo(endX, endY);
        ctx.strokeStyle = colorA;
        ctx.lineWidth = 1.8;
        ctx.stroke();
      }

      // --- DECK B: Inner Concentric Ring ---
      const waveCountB = 100;
      const peaksB = getProceduralWaveform(deckB.track?.id, waveCountB);
      const energyB = deckB.track?.energyLevel || 5;
      const colorB = getEnergyColor(energyB);
      const rB = baseRadius * 0.9;

      ctx.beginPath();
      ctx.arc(0, 0, rB, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(63, 63, 70, 0.3)";
      ctx.lineWidth = 1.5;
      ctx.stroke();

      for (let i = 0; i < waveCountB; i++) {
        const angle = (i / waveCountB) * 2 * Math.PI;
        let modifier = 1.0;
        if (i % 3 === 0) modifier += eqBassB * 0.0035;
        if (i % 3 !== 0) modifier += eqTrebleB * 0.0035;

        const peakHeight = Math.max(2, peaksB[i] * modifier);
        // Draw inwards for elegant concentric styling
        const startX = Math.cos(angle) * rB;
        const startY = Math.sin(angle) * rB;
        const endX = Math.cos(angle) * (rB - peakHeight * 0.6);
        const endY = Math.sin(angle) * (rB - peakHeight * 0.6);

        ctx.beginPath();
        ctx.moveTo(startX, startY);
        ctx.lineTo(endX, endY);
        ctx.strokeStyle = colorB;
        ctx.lineWidth = 1.6;
        ctx.stroke();
      }

      // --- CENTER ORBIT DISC ---
      ctx.beginPath();
      ctx.arc(0, 0, rB * 0.65, 0, 2 * Math.PI);
      ctx.fillStyle = "rgba(18, 18, 20, 0.9)";
      ctx.fill();
      ctx.strokeStyle = "rgba(255, 255, 255, 0.06)";
      ctx.lineWidth = 2;
      ctx.stroke();

      // Core spinning vinyl pattern
      ctx.beginPath();
      ctx.arc(0, 0, rB * 0.45, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(255, 255, 255, 0.03)";
      ctx.lineWidth = 1;
      ctx.stroke();

      ctx.beginPath();
      ctx.arc(0, 0, rB * 0.25, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(255, 255, 255, 0.03)";
      ctx.lineWidth = 1;
      ctx.stroke();

      ctx.restore();

      // --- PLAYHEAD HANDS (Chronograph-Style, Unaffected by Spinning Offset) ---
      // Deck A Playhead (Cyan stopwatch hand)
      if (deckA.track && durationA > 0) {
        const progressA = timeA / durationA;
        const angleA = progressA * 2 * Math.PI - Math.PI / 2; // Point up at 12 o'clock originally
        const handLengthA = rA + 15;

        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(cx + Math.cos(angleA) * handLengthA, cy + Math.sin(angleA) * handLengthA);
        ctx.strokeStyle = "rgba(6, 182, 212, 0.95)";
        ctx.lineWidth = 2;
        ctx.stroke();

        // Tip Node
        ctx.beginPath();
        ctx.arc(cx + Math.cos(angleA) * handLengthA, cy + Math.sin(angleA) * handLengthA, 4, 0, 2 * Math.PI);
        ctx.fillStyle = "#22d3ee";
        ctx.fill();
      }

      // Deck B Playhead (Amber stopwatch hand)
      if (deckB.track && durationB > 0) {
        const progressB = timeB / durationB;
        const angleB = progressB * 2 * Math.PI - Math.PI / 2;
        const handLengthB = rB + 10;

        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(cx + Math.cos(angleB) * handLengthB, cy + Math.sin(angleB) * handLengthB);
        ctx.strokeStyle = "rgba(245, 158, 11, 0.95)";
        ctx.lineWidth = 1.8;
        ctx.stroke();

        ctx.beginPath();
        ctx.arc(cx + Math.cos(angleB) * handLengthB, cy + Math.sin(angleB) * handLengthB, 3.5, 0, 2 * Math.PI);
        ctx.fillStyle = "#fbbf24";
        ctx.fill();
      }

      // Unified Hub Center Nut
      ctx.beginPath();
      ctx.arc(cx, cy, 8, 0, 2 * Math.PI);
      ctx.fillStyle = "#1e1b4b";
      ctx.fill();
      ctx.strokeStyle = "#e0e7ff";
      ctx.lineWidth = 2;
      ctx.stroke();

      // Tiny brass spindle cap
      ctx.beginPath();
      ctx.arc(cx, cy, 3, 0, 2 * Math.PI);
      ctx.fillStyle = "#fbbf24";
      ctx.fill();

      // Draw crossfader blending arc on outer border
      ctx.beginPath();
      ctx.arc(cx, cy, baseRadius * 1.6, Math.PI * 0.75, Math.PI * 1.25);
      ctx.strokeStyle = "rgba(63, 63, 70, 0.25)";
      ctx.lineWidth = 4;
      ctx.stroke();

      // Active blend level mark
      const blendAngle = Math.PI + (autoMixCrossfader / 100) * (Math.PI * 0.25);
      ctx.beginPath();
      ctx.arc(cx, cy, baseRadius * 1.6, blendAngle - 0.05, blendAngle + 0.05);
      ctx.strokeStyle = "#a855f7";
      ctx.lineWidth = 6;
      ctx.stroke();

      // Indicator label for Active Deck
      ctx.fillStyle = activeDeckFocus === "A" ? "#22d3ee" : "#fbbf24";
      ctx.font = "bold 9px monospace";
      ctx.textAlign = "center";
      ctx.fillText(`CONTROL TARGET: DECK ${activeDeckFocus}`, cx, cy - rB * 1.45);

      animId = requestAnimationFrame(render);
    };

    render();
    return () => cancelAnimationFrame(animId);
  }, [
    deckA.track, deckB.track, timeA, timeB, durationA, durationB, 
    eqBassA, eqTrebleA, eqBassB, eqTrebleB, wheelRotation, autoMixCrossfader, activeDeckFocus
  ]);

  // Handle touch interactions
  const handleTouchStart = (e: React.TouchEvent) => {
    e.preventDefault();
    const touches = Array.from(e.touches as any).map((t: any) => {
      const rect = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
      return { x: t.clientX - rect.left, y: t.clientY - rect.top };
    });

    touchStartRef.current = touches;
    touchStartCrossfaderRef.current = autoMixCrossfader;
    touchStartPitchRef.current = activeDeckFocus === "A" ? deckA.pitch : deckB.pitch;
    touchStartVolumeRef.current = audioVolume;
    touchStartWheelRotationRef.current = wheelRotation;

    if (touches.length === 2) {
      const t0 = touches[0];
      const t1 = touches[1];
      touchStartDistRef.current = Math.hypot(t1.x - t0.x, t1.y - t0.y);
      touchStartAngleRef.current = Math.atan2(t1.y - t0.y, t1.x - t0.x);
    }
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    e.preventDefault();
    if (touchStartRef.current.length === 0) return;

    const rect = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
    const touches = Array.from(e.touches as any).map((t: any) => ({
      x: t.clientX - rect.left,
      y: t.clientY - rect.top
    }));

    const activeDeck = activeDeckFocus === "A" ? deckA : deckB;
    const setDeck = activeDeckFocus === "A" ? setDeckA : setDeckB;

    // --- 1 FINGER GESTURES ---
    if (touches.length === 1 && touchStartRef.current.length === 1) {
      const start = touchStartRef.current[0];
      const curr = touches[0];
      const dx = curr.x - start.x;
      const dy = curr.y - start.y;

      // Vertical Drag -> Adjusts Pitch (-8% to +8%)
      const pitchDelta = -dy * 0.05; // 0.05% pitch shift per pixel
      const newPitch = Math.max(-8, Math.min(8, touchStartPitchRef.current + pitchDelta));
      setDeck(prev => ({ ...prev, pitch: Number(newPitch.toFixed(3)) }));

      // Horizontal Drag -> Adjusts Bass/Treble EQ Balance
      if (activeDeckFocus === "A") {
        setEqBassA(Math.max(-100, Math.min(100, Math.round(dx * 0.85))));
        setEqTrebleA(Math.max(-100, Math.min(100, Math.round(-dx * 0.85))));
      } else {
        setEqBassB(Math.max(-100, Math.min(100, Math.round(dx * 0.85))));
        setEqTrebleB(Math.max(-100, Math.min(100, Math.round(-dx * 0.85))));
      }
    }

    // --- 2 FINGER GESTURES ---
    if (touches.length === 2 && touchStartRef.current.length === 2) {
      const t0 = touches[0];
      const t1 = touches[1];
      const currDist = Math.hypot(t1.x - t0.x, t1.y - t0.y);
      const currAngle = Math.atan2(t1.y - t0.y, t1.x - t0.x);

      // A. Pinch to Zoom (distance) -> slows down or speeds up the bpm
      const distRatio = currDist / touchStartDistRef.current;
      const currentPitchVal = touchStartPitchRef.current;
      const pitchRatioMod = (distRatio - 1) * 15; // Zoom scale factor
      const nextPitch = Math.max(-8, Math.min(8, currentPitchVal + pitchRatioMod));
      setDeck(prev => ({ ...prev, pitch: Number(nextPitch.toFixed(3)) }));

      // B. Two Finger Rotation -> adjusts overlap (phase offset)
      const angleDiff = currAngle - touchStartAngleRef.current;
      setDeck(prev => ({
        ...prev,
        phaseOffset: Number(((prev.phaseOffset + angleDiff * 5) % 360).toFixed(2))
      }));

      // C. Two Finger Vertical (Up/Down) centroid shift -> adjusts crossfade
      const startCentroidY = (touchStartRef.current[0].y + touchStartRef.current[1].y) / 2;
      const currCentroidY = (t0.y + t1.y) / 2;
      const centroidDy = currCentroidY - startCentroidY;
      const newCrossfader = Math.max(-100, Math.min(100, Math.round(touchStartCrossfaderRef.current + centroidDy * 0.6)));
      setAutoMixCrossfader(newCrossfader);

      // D. Two Finger Horizontal (Left/Right) centroid shift -> rewind / fast-forward (seek)
      const startCentroidX = (touchStartRef.current[0].x + touchStartRef.current[1].x) / 2;
      const currCentroidX = (t0.x + t1.x) / 2;
      const centroidDx = currCentroidX - startCentroidX;
      if (Math.abs(centroidDx) > 8) {
        const currentTime = activeDeckFocus === "A" ? timeA : timeB;
        const duration = activeDeckFocus === "A" ? durationA : durationB;
        const seekOffset = centroidDx * 0.08; // 0.08 seconds per pixel
        const nextTime = Math.max(0, Math.min(duration, currentTime + seekOffset));
        handleSeek(activeDeckFocus, nextTime);
      }
    }

    // --- 3 FINGER GESTURES ---
    if (touches.length === 3 && touchStartRef.current.length === 3) {
      // Centroid calculations for 3 touches
      const t0 = touches[0];
      const t1 = touches[1];
      const t2 = touches[2];
      const cx = (t0.x + t1.x + t2.x) / 3;
      const cy = (t0.y + t1.y + t2.y) / 3;

      const scx = (touchStartRef.current[0].x + touchStartRef.current[1].x + touchStartRef.current[2].x) / 3;
      const scy = (touchStartRef.current[0].y + touchStartRef.current[1].y + touchStartRef.current[2].y) / 3;

      // A. Three Finger Rotation -> spins the circle around
      const currAngle = Math.atan2(cy - cy, cx - cx) || 0;
      const angleDelta = (cx - scx) * 0.0075; // Rotate wheel based on drift
      setWheelRotation(touchStartWheelRotationRef.current + angleDelta);

      // B. Three Finger Pinch (bounding size) -> adjusts volume
      const currentArea = Math.hypot(t1.x - t0.x, t1.y - t0.y) + Math.hypot(t2.x - t1.x, t2.y - t1.y);
      const startArea = Math.hypot(touchStartRef.current[1].x - touchStartRef.current[0].x, touchStartRef.current[1].y - touchStartRef.current[0].y) + 
                        Math.hypot(touchStartRef.current[2].x - touchStartRef.current[1].x, touchStartRef.current[2].y - touchStartRef.current[1].y);
      
      const areaRatio = currentArea / (startArea || 1);
      const targetVolume = Math.max(0, Math.min(0.8, touchStartVolumeRef.current * areaRatio));
      setAudioVolume(Number(targetVolume.toFixed(2)));
    }
  };

  const handleTouchEnd = () => {
    touchStartRef.current = [];
  };

  // DESKTOP MOUSE EMULATOR HANDLERS
  const handleMouseDown = (e: React.MouseEvent) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    setIsSimulating(true);
    setLastMousePos({ x, y });

    touchStartCrossfaderRef.current = autoMixCrossfader;
    touchStartPitchRef.current = activeDeckFocus === "A" ? deckA.pitch : deckB.pitch;
    touchStartVolumeRef.current = audioVolume;
    touchStartWheelRotationRef.current = wheelRotation;
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!isSimulating || !lastMousePos) return;

    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    const dx = x - lastMousePos.x;
    const dy = y - lastMousePos.y;

    const activeDeck = activeDeckFocus === "A" ? deckA : deckB;
    const setDeck = activeDeckFocus === "A" ? setDeckA : setDeckB;

    if (fingerMode === 1) {
      // 1 Finger: Vertically adjusts pitch, Horizontally adjusts Bass/Treble EQ balance
      const pitchDelta = -dy * 0.04;
      const nextPitch = Math.max(-8, Math.min(8, (activeDeckFocus === "A" ? deckA.pitch : deckB.pitch) + pitchDelta));
      setDeck(prev => ({ ...prev, pitch: Number(nextPitch.toFixed(3)) }));

      if (activeDeckFocus === "A") {
        setEqBassA(prev => Math.max(-100, Math.min(100, prev + Math.round(dx * 0.8))));
        setEqTrebleA(prev => Math.max(-100, Math.min(100, prev + Math.round(-dx * 0.8))));
      } else {
        setEqBassB(prev => Math.max(-100, Math.min(100, prev + Math.round(dx * 0.8))));
        setEqTrebleB(prev => Math.max(-100, Math.min(100, prev + Math.round(-dx * 0.8))));
      }
    } else if (fingerMode === 2) {
      // 2 Finger Emulator Modifiers
      if (e.shiftKey) {
        // Shift + Drag -> Two Finger Rotation -> Adjusts Overlap (Phase Offset)
        const angleMod = dx * 0.04;
        setDeck(prev => ({
          ...prev,
          phaseOffset: Number(((prev.phaseOffset + angleMod * 10) % 360).toFixed(2))
        }));
      } else if (e.altKey) {
        // Alt + Drag -> Two Finger Horizontal -> Rewind / Fast-Forward
        const currentTime = activeDeckFocus === "A" ? timeA : timeB;
        const duration = activeDeckFocus === "A" ? durationA : durationB;
        const seekOffset = dx * 0.12;
        const nextTime = Math.max(0, Math.min(duration, currentTime + seekOffset));
        handleSeek(activeDeckFocus, nextTime);
      } else {
        // Normal Drag -> Two Finger Vertical (Up/Down) -> Adjusts Crossfade
        const newCross = Math.max(-100, Math.min(100, Math.round(autoMixCrossfader + dy * 0.5)));
        setAutoMixCrossfader(newCross);
      }
    } else if (fingerMode === 3) {
      if (e.shiftKey) {
        // Shift + Drag -> Three Finger Pinch (Volume)
        const volMod = -dy * 0.005;
        setAudioVolume(Math.max(0, Math.min(0.8, Number((audioVolume + volMod).toFixed(2)))));
      } else {
        // Normal Drag -> Three Finger Rotation -> Spins circle
        const spinMod = dx * 0.004;
        setWheelRotation(prev => prev + spinMod);
      }
    }

    setLastMousePos({ x, y });
  };

  const handleMouseUp = () => {
    setIsSimulating(false);
    setLastMousePos(null);
  };

  const handleWheel = (e: React.WheelEvent) => {
    // Wheel scrolls simulate 2-finger pinch (zoom) -> speeds/slows BPM
    const setDeck = activeDeckFocus === "A" ? setDeckA : setDeckB;
    const currentPitch = activeDeckFocus === "A" ? deckA.pitch : deckB.pitch;
    const pitchDelta = e.deltaY > 0 ? -0.15 : 0.15; // scroll up speeds up, scroll down slows down
    const nextPitch = Math.max(-8, Math.min(8, currentPitch + pitchDelta));
    setDeck(prev => ({ ...prev, pitch: Number(nextPitch.toFixed(3)) }));
    e.preventDefault();
  };

  // Key stats calculations
  const displayBpmA = (deckA.baseBpm * (1 + deckA.pitch / 100)).toFixed(1);
  const displayBpmB = (deckB.baseBpm * (1 + deckB.pitch / 100)).toFixed(1);

  return (
    <div className="space-y-6 pt-1" id="radial-controller-module">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        
        {/* Left Side: Tactile Info & Sliders */}
        <div className="lg:col-span-4 space-y-5 bg-zinc-900/40 border border-zinc-850 p-4 rounded-xl shadow-lg">
          <div className="flex items-center justify-between border-b border-zinc-800 pb-3">
            <h4 className="text-xs font-black text-zinc-100 uppercase tracking-widest flex items-center gap-1.5">
              <Orbit size={13} className="text-cyan-400 animate-pulse" />
              <span>Tactile Desk Stats</span>
            </h4>
            <div className="flex gap-1">
              <button
                onClick={() => setActiveDeckFocus("A")}
                className={`text-[9px] font-black font-mono px-2 py-1 rounded cursor-pointer transition-all ${activeDeckFocus === "A" ? "bg-cyan-500 text-zinc-950" : "bg-zinc-800 text-zinc-400 hover:text-white"}`}
              >
                DECK A
              </button>
              <button
                onClick={() => setActiveDeckFocus("B")}
                className={`text-[9px] font-black font-mono px-2 py-1 rounded cursor-pointer transition-all ${activeDeckFocus === "B" ? "bg-amber-500 text-zinc-950" : "bg-zinc-800 text-zinc-400 hover:text-white"}`}
              >
                DECK B
              </button>
            </div>
          </div>

          {/* Quick Stats Grid */}
          <div className="grid grid-cols-2 gap-3">
            <div className="bg-zinc-950/70 border border-zinc-850/80 p-2.5 rounded-lg space-y-0.5">
              <span className="text-[8px] font-bold text-zinc-500 uppercase">DECK A SPEED</span>
              <p className="text-sm font-black font-mono text-cyan-400">{displayBpmA} <span className="text-[8px] text-zinc-500 font-bold">BPM</span></p>
              <p className="text-[9px] font-mono text-zinc-400 font-bold">Pitch: {deckA.pitch > 0 ? "+" : ""}{deckA.pitch.toFixed(2)}%</p>
            </div>
            <div className="bg-zinc-950/70 border border-zinc-850/80 p-2.5 rounded-lg space-y-0.5">
              <span className="text-[8px] font-bold text-zinc-500 uppercase">DECK B SPEED</span>
              <p className="text-sm font-black font-mono text-amber-400">{displayBpmB} <span className="text-[8px] text-zinc-500 font-bold">BPM</span></p>
              <p className="text-[9px] font-mono text-zinc-400 font-bold">Pitch: {deckB.pitch > 0 ? "+" : ""}{deckB.pitch.toFixed(2)}%</p>
            </div>
          </div>

          {/* Current EQ values */}
          <div className="space-y-3.5 bg-zinc-950/40 p-3 rounded-lg border border-zinc-850/60 text-3xs font-mono">
            <span className="text-[8px] font-black text-zinc-400 uppercase tracking-wider block">Real-time EQ Response</span>
            
            <div className="space-y-1.5">
              <div className="flex justify-between font-bold text-zinc-400">
                <span>DECK A BASS: {eqBassA > 0 ? "+" : ""}{eqBassA}%</span>
                <span>TREBLE: {eqTrebleA > 0 ? "+" : ""}{eqTrebleA}%</span>
              </div>
              <div className="h-1.5 bg-zinc-900 rounded overflow-hidden flex">
                <div className="h-full bg-cyan-600 transition-all" style={{ width: `${Math.abs(eqBassA)}%`, marginLeft: eqBassA < 0 ? "auto" : "0" }}></div>
                <div className="h-full bg-cyan-400 transition-all ml-auto" style={{ width: `${Math.abs(eqTrebleA)}%` }}></div>
              </div>
            </div>

            <div className="space-y-1.5">
              <div className="flex justify-between font-bold text-zinc-400">
                <span>DECK B BASS: {eqBassB > 0 ? "+" : ""}{eqBassB}%</span>
                <span>TREBLE: {eqTrebleB > 0 ? "+" : ""}{eqTrebleB}%</span>
              </div>
              <div className="h-1.5 bg-zinc-900 rounded overflow-hidden flex">
                <div className="h-full bg-amber-600 transition-all" style={{ width: `${Math.abs(eqBassB)}%`, marginLeft: eqBassB < 0 ? "auto" : "0" }}></div>
                <div className="h-full bg-amber-400 transition-all ml-auto" style={{ width: `${Math.abs(eqTrebleB)}%` }}></div>
              </div>
            </div>
          </div>

          {/* Crossfader blending info */}
          <div className="space-y-2 text-3xs">
            <div className="flex justify-between items-center text-zinc-400">
              <span className="font-bold">CROSSFADER MATRIX</span>
              <span className="font-bold text-purple-400 font-mono">{autoMixCrossfader}%</span>
            </div>
            <input
              type="range"
              min="-100"
              max="100"
              value={autoMixCrossfader}
              onChange={(e) => setAutoMixCrossfader(parseInt(e.target.value, 10))}
              className="w-full accent-purple-500 h-1.5 bg-zinc-950 rounded cursor-pointer"
            />
          </div>

          {/* Volume Knob */}
          <div className="space-y-2 text-3xs">
            <div className="flex justify-between items-center text-zinc-400">
              <span className="font-bold">MASTER GAIN VOLUME</span>
              <span className="font-bold text-emerald-400 font-mono">{Math.round(audioVolume * 125)}%</span>
            </div>
            <input
              type="range"
              min="0"
              max="0.8"
              step="0.05"
              value={audioVolume}
              onChange={(e) => setAudioVolume(parseFloat(e.target.value))}
              className="w-full accent-emerald-500 h-1.5 bg-zinc-950 rounded cursor-pointer"
            />
          </div>

          <div className="flex items-center gap-2 pt-1 border-t border-zinc-800">
            <button
              onClick={() => {
                setEqBassA(0);
                setEqTrebleA(0);
                setEqBassB(0);
                setEqTrebleB(0);
                setWheelRotation(0);
                setFeedbackMsg("Reset all tactile parameter offsets.");
              }}
              className="w-full flex items-center justify-center gap-1.5 py-2 border border-zinc-800 hover:border-zinc-700 bg-zinc-950 hover:bg-zinc-900 text-[10px] font-black uppercase text-zinc-400 hover:text-white rounded-lg transition-colors cursor-pointer"
            >
              <RotateCcw size={11} />
              Reset EQ & Rotate
            </button>
          </div>
        </div>

        {/* Center: The Interactive Visualizer Canvas */}
        <div className="lg:col-span-5 flex flex-col items-center justify-center space-y-4" ref={containerRef}>
          <div 
            className="relative bg-zinc-950 border border-zinc-850 rounded-2xl overflow-hidden shadow-2xl flex items-center justify-center cursor-crosshair select-none touch-none"
            style={{ width: `${dimensions.width}px`, height: `${dimensions.height}px` }}
            onTouchStart={handleTouchStart}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUp}
            onMouseLeave={handleMouseUp}
            onWheel={handleWheel}
            id="radial-gesture-canvas-container"
          >
            <canvas 
              ref={canvasRef} 
              width={dimensions.width} 
              height={dimensions.height}
              className="absolute inset-0 block"
            />
          </div>

          {/* Quick Play Controls for fast feedback */}
          <div className="flex items-center gap-2 bg-zinc-900/60 p-2 border border-zinc-850 rounded-xl shadow-md">
            <button
              onClick={() => setIsPlaying(!isPlaying)}
              className={`flex items-center justify-center gap-1.5 px-5 py-2 text-zinc-950 text-xs font-black uppercase tracking-widest rounded-lg cursor-pointer transition-all ${isPlaying ? "bg-amber-500 hover:bg-amber-400" : "bg-cyan-500 hover:bg-cyan-400"}`}
            >
              {isPlaying ? <Square size={12} fill="currentColor" /> : <Play size={12} fill="currentColor" />}
              <span>{isPlaying ? "HALT" : "PLAY"}</span>
            </button>
            <span className="text-[10px] text-zinc-400 px-3 border-l border-zinc-800 font-bold font-mono">
              TIME: {Math.round(timeA)}s / {Math.round(timeB)}s
            </span>
          </div>
        </div>

        {/* Right Side: Gestures Instructions & Desktop Simulator Touchpad */}
        <div className="lg:col-span-3 space-y-5">
          
          {/* Desktop Emulator Pad */}
          <div className="bg-zinc-900/40 border border-zinc-850 p-4 rounded-xl shadow-lg space-y-4">
            <div className="flex justify-between items-center border-b border-zinc-800 pb-2.5">
              <h5 className="text-[10px] font-black text-zinc-100 uppercase tracking-widest flex items-center gap-1">
                <Settings2 size={12} className="text-purple-400" />
                <span>Desktop Gesture Pad</span>
              </h5>
              <span className="text-[8px] bg-purple-950 text-purple-400 border border-purple-900 px-1.5 py-0.5 rounded font-mono font-bold">EMULATOR</span>
            </div>

            <p className="text-4xs text-zinc-400 leading-relaxed">
              No touchscreen? Select a mode below, then click & drag/scroll directly inside the spinning visualizer circle to simulate high-fidelity multi-touch gestures!
            </p>

            {/* Mode Selectors */}
            <div className="grid grid-cols-3 gap-1.5 text-3xs font-mono font-bold">
              <button
                onClick={() => setFingerMode(1)}
                className={`py-1.5 rounded border transition-all cursor-pointer ${fingerMode === 1 ? "bg-cyan-500 text-zinc-950 border-cyan-400" : "bg-zinc-950 text-zinc-400 border-zinc-800 hover:text-white"}`}
              >
                1 FINGER
              </button>
              <button
                onClick={() => setFingerMode(2)}
                className={`py-1.5 rounded border transition-all cursor-pointer ${fingerMode === 2 ? "bg-purple-500 text-zinc-950 border-purple-400" : "bg-zinc-950 text-zinc-400 border-zinc-800 hover:text-white"}`}
              >
                2 FINGER
              </button>
              <button
                onClick={() => setFingerMode(3)}
                className={`py-1.5 rounded border transition-all cursor-pointer ${fingerMode === 3 ? "bg-emerald-500 text-zinc-950 border-emerald-400" : "bg-zinc-950 text-zinc-400 border-zinc-800 hover:text-white"}`}
              >
                3 FINGER
              </button>
            </div>

            {/* Gesture Mapping Legend based on Mode */}
            <div className="bg-zinc-950/80 border border-zinc-850 p-3 rounded-lg text-4xs space-y-2 leading-relaxed font-mono">
              <span className="text-[8px] font-black text-purple-400 uppercase tracking-wider block">Mode Mapping:</span>
              
              {fingerMode === 1 && (
                <ul className="space-y-1.5 text-zinc-400">
                  <li><strong className="text-cyan-400">• Drag Vertically:</strong> Adjust active deck speed pitch fader</li>
                  <li><strong className="text-cyan-400">• Drag Horizontally:</strong> Swell/tilt Bass and Treble EQ balance</li>
                  <li><strong className="text-zinc-500">• Scroll Wheel:</strong> Micro-adjust speed/BPM fader</li>
                </ul>
              )}

              {fingerMode === 2 && (
                <ul className="space-y-1.5 text-zinc-400">
                  <li><strong className="text-purple-400">• Scroll Wheel:</strong> Pinch to Zoom → speeds/slows down active BPM</li>
                  <li><strong className="text-purple-400">• Drag Vertically:</strong> Adjust crossfader blending</li>
                  <li><strong className="text-purple-400">• Shift + Drag:</strong> Two-finger rotation → adjusts grid overlap</li>
                  <li><strong className="text-purple-400">• Alt + Drag:</strong> Two-finger horizontal swipe → rewind / fast-forward</li>
                </ul>
              )}

              {fingerMode === 3 && (
                <ul className="space-y-1.5 text-zinc-400">
                  <li><strong className="text-emerald-400">• Drag Horizontally:</strong> Spins the visualizer circle around</li>
                  <li><strong className="text-emerald-400">• Shift + Drag:</strong> Bounding area pinch → adjusts master volume</li>
                </ul>
              )}
            </div>
          </div>

          {/* Touch Gesture Legend */}
          <div className="bg-zinc-900/40 border border-zinc-850 p-4 rounded-xl shadow-lg space-y-3.5">
            <h5 className="text-[10px] font-black text-zinc-100 uppercase tracking-widest flex items-center gap-1.5">
              <Hand size={12} className="text-cyan-400" />
              <span>Multi-Touch Guide</span>
            </h5>
            
            <div className="space-y-2.5 text-4xs font-mono leading-relaxed text-zinc-400">
              <div className="flex gap-2 items-start">
                <span className="text-cyan-400 font-bold">1F:</span>
                <p>Drag Vertically for Pitch. Drag Horizontally for Bass/Treble EQ filters.</p>
              </div>
              <div className="flex gap-2 items-start">
                <span className="text-purple-400 font-bold">2F:</span>
                <p>Pinch to Zoom (BPM). Rotate for Overlap (Phase). Drag Vertically for Crossfader. Drag Horizontally to Rewind/FF.</p>
              </div>
              <div className="flex gap-2 items-start">
                <span className="text-emerald-400 font-bold">3F:</span>
                <p>Rotate for Circle Spin. Pinch in/out to adjust Master Volume.</p>
              </div>
            </div>
          </div>

        </div>

      </div>
    </div>
  );
}
