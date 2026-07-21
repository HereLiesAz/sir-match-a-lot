import React, { useState, useEffect, useRef } from "react";
import { Sliders, Sparkles, Move, Zap, HelpCircle } from "lucide-react";

interface EnergyGraphProps {
  bpmA: number;
  bpmB: number;
  pitchA: number;
  pitchB: number;
  isPlaying: boolean;
}

export default function EnergyGraph({ bpmA, bpmB, pitchA, pitchB, isPlaying }: EnergyGraphProps) {
  // Timeline length: 16 bars/points
  const [points, setPoints] = useState<number[]>([
    20, 30, 45, 40, 55, 70, 85, 75, 90, 95, 80, 65, 50, 40, 30, 15
  ]);
  const [activePlayhead, setActivePlayhead] = useState(0);
  const [showGraphHelp, setShowGraphHelp] = useState(false);
  const [interactionMode, setInteractionMode] = useState<"draw" | "scale" | "shift">("draw");

  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Dragging state
  const isDraggingRef = useRef(false);
  const dragStartRef = useRef<{ x: number; y: number; originalPoints: number[] }>({ x: 0, y: 0, originalPoints: [] });
  const touchStartDistRef = useRef<number | null>(null);

  // Dynamic multiplier based on BPM & Pitch
  const averageBpm = (bpmA + bpmB) / 2;
  const bpmFactor = Math.min(1.4, Math.max(0.6, averageBpm / 120));
  const pitchFactor = 1 + (pitchA + pitchB) / 200; // slightly alters base line

  // Get active adjusted energy at any point
  const getAdjustedPoints = (): number[] => {
    return points.map(p => {
      // Energy scale from 0 to 100
      const adjusted = p * bpmFactor * pitchFactor;
      return Math.min(100, Math.max(5, Math.round(adjusted)));
    });
  };

  const adjustedPoints = getAdjustedPoints();

  // Scroll active playhead if playing
  useEffect(() => {
    if (!isPlaying) return;
    const interval = setInterval(() => {
      setActivePlayhead(prev => (prev + 1) % 16);
    }, 120000 / averageBpm); // advance every 2 beats based on current average BPM
    return () => clearInterval(interval);
  }, [isPlaying, averageBpm]);

  // Handle Resize of canvas
  useEffect(() => {
    const handleResize = () => {
      const canvas = canvasRef.current;
      const container = containerRef.current;
      if (canvas && container) {
        canvas.width = container.clientWidth;
        canvas.height = 220;
        drawGraph();
      }
    };
    handleResize();

    const observer = new ResizeObserver(handleResize);
    if (containerRef.current) {
      observer.observe(containerRef.current);
    }
    return () => observer.disconnect();
  }, [points, bpmA, bpmB, pitchA, pitchB, activePlayhead]);

  // Redraw whenever parameters change
  useEffect(() => {
    drawGraph();
  }, [points, bpmA, bpmB, pitchA, pitchB, activePlayhead]);

  const drawGraph = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const width = canvas.width;
    const height = canvas.height;

    // Reset background
    ctx.fillStyle = "#09090b"; // zinc-950
    ctx.fillRect(0, 0, width, height);

    // Draw background grid lines (vertical and horizontal)
    ctx.strokeStyle = "#18181b"; // zinc-900
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = (height - 30) * (i / 4) + 15;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();

      // Label energy levels on right
      ctx.fillStyle = "#3f3f46"; // zinc-700
      ctx.font = "9px monospace";
      ctx.fillText(`${100 - i * 25}%`, width - 30, y - 4);
    }

    const colWidth = width / 15;

    // Transition Zones Markers
    const zones = [
      { name: "CUE / INTRO", start: 0, end: 3, color: "#06b6d422", textColor: "#22d3ee" },
      { name: "MIX BLEND", start: 3, end: 7, color: "#3b82f615", textColor: "#60a5fa" },
      { name: "PEAK ENERGY", start: 7, end: 12, color: "#8b5cf618", textColor: "#a78bfa" },
      { name: "OUTRO / CUE B", start: 12, end: 15, color: "#ec489911", textColor: "#f472b6" }
    ];

    zones.forEach(z => {
      const startX = z.start * colWidth;
      const endX = z.end * colWidth;
      ctx.fillStyle = z.color;
      ctx.fillRect(startX, 0, endX - startX, height);

      // Label zones at top
      ctx.fillStyle = z.textColor;
      ctx.font = "bold 9px system-ui";
      ctx.fillText(z.name, startX + 10, 18);
    });

    // Draw continuous energy line curve
    const gradient = ctx.createLinearGradient(0, 0, width, 0);
    gradient.addColorStop(0, "#06b6d4"); // cyan
    gradient.addColorStop(0.3, "#3b82f6"); // blue
    gradient.addColorStop(0.7, "#8b5cf6"); // purple
    gradient.addColorStop(1, "#ec4899"); // pink

    ctx.strokeStyle = gradient;
    ctx.lineWidth = 4;
    ctx.beginPath();

    const getX = (index: number) => index * colWidth;
    const getY = (val: number) => {
      const percent = val / 100;
      // Keep within bounds of grid padding
      const maxH = height - 50;
      return height - 25 - percent * maxH;
    };

    // Draw smooth curve using bezier control points
    ctx.moveTo(getX(0), getY(adjustedPoints[0]));
    for (let i = 0; i < adjustedPoints.length - 1; i++) {
      const x1 = getX(i);
      const y1 = getY(adjustedPoints[i]);
      const x2 = getX(i + 1);
      const y2 = getY(adjustedPoints[i + 1]);
      const xc = (x1 + x2) / 2;
      const yc = (y1 + y2) / 2;
      ctx.quadraticCurveTo(x1, y1, xc, yc);
    }
    ctx.lineTo(getX(15), getY(adjustedPoints[15]));
    ctx.stroke();

    // Fill area under curve with translucency
    ctx.lineTo(width, height - 20);
    ctx.lineTo(0, height - 20);
    ctx.closePath();
    const fillGradient = ctx.createLinearGradient(0, 0, 0, height);
    fillGradient.addColorStop(0, "#3b82f625");
    fillGradient.addColorStop(1, "#00000000");
    ctx.fillStyle = fillGradient;
    ctx.fill();

    // Draw dots at point intervals
    adjustedPoints.forEach((val, i) => {
      const x = getX(i);
      const y = getY(val);

      // Active playing point gets larger pulse
      if (i === activePlayhead && isPlaying) {
        ctx.shadowColor = "#22d3ee";
        ctx.shadowBlur = 10;
        ctx.fillStyle = "#ffffff";
        ctx.beginPath();
        ctx.arc(x, y, 7, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0; // reset
      } else {
        ctx.fillStyle = i % 4 === 0 ? "#ffffff" : "#a1a1aa";
        ctx.beginPath();
        ctx.arc(x, y, i % 4 === 0 ? 4 : 3, 0, Math.PI * 2);
        ctx.fill();
      }

      // Draw value text above the points
      ctx.fillStyle = "#71717a"; // zinc-500
      ctx.font = "8px monospace";
      ctx.fillText(`${val}`, x - 6, y - 8);
    });

    // Draw bar timeline labels at bottom
    ctx.fillStyle = "#52525b"; // zinc-600
    ctx.font = "bold 9px monospace";
    for (let i = 0; i < 16; i++) {
      const x = getX(i);
      ctx.fillText(`BAR ${i + 1}`, x - 12, height - 6);
    }
  };

  // Process mouse interactions and reshape curve
  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    isDraggingRef.current = true;
    dragStartRef.current = { x, y, originalPoints: [...points] };

    // If Right click OR shift key is pressed, force "scale" mode
    if (e.button === 2 || e.shiftKey) {
      setInteractionMode("scale");
      return;
    }

    if (interactionMode === "draw") {
      updatePointFromCoords(x, y);
    }
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDraggingRef.current) return;
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    if (interactionMode === "draw" && !e.shiftKey) {
      updatePointFromCoords(x, y);
    } else if (interactionMode === "scale" || e.shiftKey) {
      // Stretch or scale amplitude based on relative drag vector from start
      const deltaY = y - dragStartRef.current.y;
      const deltaX = x - dragStartRef.current.x;

      // Vertical drag stretches height amplitude
      const ampFactor = 1 - deltaY / 150;
      // Horizontal drag stretches timeline index
      const stretchFactor = 1 + deltaX / 200;

      const newPoints = dragStartRef.current.originalPoints.map((p, idx) => {
        // Shift point relative to center or stretch it out
        const centerIndex = 7.5;
        const stretchedIdx = Math.round(centerIndex + (idx - centerIndex) * stretchFactor);
        const sourcePoint = dragStartRef.current.originalPoints[Math.min(15, Math.max(0, stretchedIdx))] || p;
        return Math.min(100, Math.max(5, Math.round(sourcePoint * ampFactor)));
      });

      setPoints(newPoints);
    } else if (interactionMode === "shift") {
      // Shift curve vertically or horizontally
      const deltaY = y - dragStartRef.current.y;
      const verticalShift = Math.round(-deltaY / 2);
      const newPoints = dragStartRef.current.originalPoints.map(p => {
        return Math.min(100, Math.max(5, p + verticalShift));
      });
      setPoints(newPoints);
    }
  };

  const handleMouseUpOrLeave = () => {
    isDraggingRef.current = false;
  };

  // Convert canvas cursor coordinates to a specific Bar Node and Energy height
  const updatePointFromCoords = (canvasX: number, canvasY: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const width = canvas.width;
    const height = canvas.height;

    const colWidth = width / 15;
    const index = Math.round(canvasX / colWidth);
    if (index < 0 || index > 15) return;

    // Map Y coordinate to energy scale (0-100)
    const maxH = height - 50;
    const relativeY = height - 25 - canvasY;
    const percent = Math.min(100, Math.max(0, (relativeY / maxH) * 100));

    // Convert back from current BPM factor to keep baseline points correct
    const rawVal = percent / (bpmFactor * pitchFactor);
    const newPoints = [...points];
    newPoints[index] = Math.min(100, Math.max(5, Math.round(rawVal)));
    setPoints(newPoints);
  };

  // Mobile / Tablet Touch Gesture Handler (Supports multi-touch 1 vs 2 fingers)
  const handleTouchStart = (e: React.TouchEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas || e.touches.length === 0) return;

    const rect = canvas.getBoundingClientRect();
    isDraggingRef.current = true;

    if (e.touches.length === 1) {
      const x = e.touches[0].clientX - rect.left;
      const y = e.touches[0].clientY - rect.top;
      dragStartRef.current = { x, y, originalPoints: [...points] };
      touchStartDistRef.current = null;

      if (interactionMode === "draw") {
        updatePointFromCoords(x, y);
      }
    } else if (e.touches.length === 2) {
      // Two-finger pinch tracking
      const t1 = e.touches[0];
      const t2 = e.touches[1];
      const dx = t1.clientX - t2.clientX;
      const dy = t1.clientY - t2.clientY;
      const dist = Math.sqrt(dx * dx + dy * dy);

      touchStartDistRef.current = dist;
      dragStartRef.current = {
        x: (t1.clientX + t2.clientX) / 2 - rect.left,
        y: (t1.clientY + t2.clientY) / 2 - rect.top,
        originalPoints: [...points]
      };
      setInteractionMode("scale");
    }
  };

  const handleTouchMove = (e: React.TouchEvent<HTMLCanvasElement>) => {
    if (!isDraggingRef.current) return;
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();

    if (e.touches.length === 1 && interactionMode === "draw") {
      const x = e.touches[0].clientX - rect.left;
      const y = e.touches[0].clientY - rect.top;
      updatePointFromCoords(x, y);
    } else if (e.touches.length === 2 && touchStartDistRef.current) {
      // Calculate active pinch scale
      const t1 = e.touches[0];
      const t2 = e.touches[1];
      const dx = t1.clientX - t2.clientX;
      const dy = t1.clientY - t2.clientY;
      const dist = Math.sqrt(dx * dx + dy * dy);

      const ratio = dist / touchStartDistRef.current;
      const newPoints = dragStartRef.current.originalPoints.map((p, idx) => {
        const centerIndex = 7.5;
        // Expand/compress index relative to central point
        const stretchedIdx = Math.round(centerIndex + (idx - centerIndex) / ratio);
        const sourcePoint = dragStartRef.current.originalPoints[Math.min(15, Math.max(0, stretchedIdx))] || p;
        return Math.min(100, Math.max(5, Math.round(sourcePoint)));
      });
      setPoints(newPoints);
    }
  };

  // Generate Vibe recommendations based on the current shape of the graph
  const getMixRecommendation = () => {
    const peak = Math.max(...adjustedPoints);
    const end = adjustedPoints[15];
    const mid = adjustedPoints[7];

    if (peak > 85 && end < 35) {
      return {
        vibe: "Intense Drop & Quick Outro",
        desc: "High visual spike matches raw peak time tech-house energy. Plan a quick blend mix to transition safely before energy bottoms out.",
        score: "Peak Score: 96/100"
      };
    } else if (Math.abs(peak - end) < 15 && peak > 70) {
      return {
        vibe: "Sustained Peak Power",
        desc: "Steady elevated energy is excellent for main-room techno loops. Keep channels fully open and loop key vocal stems for high impact.",
        score: "Peak Score: 92/100"
      };
    } else if (mid > peak * 0.8 && end > peak * 0.7) {
      return {
        vibe: "Progressive Smooth Transition",
        desc: "Ideal for warm-up deep grooves. Smooth gradual builder permits a 4-minute harmonic blend without clashing transients.",
        score: "Peak Score: 88/100"
      };
    } else {
      return {
        vibe: "Classic Dynamic Wave",
        desc: "Perfect dynamic narrative for open-format DJ sets. Introduces a steady build, peak impact, and gentle tail-off for the next track.",
        score: "Peak Score: 84/100"
      };
    }
  };

  const rec = getMixRecommendation();

  return (
    <div className="bg-zinc-900 border border-zinc-850 p-4 rounded-xl space-y-4">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2 pb-3 border-b border-zinc-850">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="text-cyan-400" size={16} />
            <span className="text-sm font-bold uppercase tracking-wider text-zinc-100">Live Session Energy Graph</span>
          </div>
          <p className="text-3xs text-zinc-400">Interactive session timeline mapped from BPM ({averageBpm.toFixed(0)} Avg) & pitch offsets.</p>
        </div>

        <div className="flex items-center gap-2 w-full sm:w-auto">
          {/* Mode switch */}
          <div className="flex border border-zinc-800 bg-zinc-950 p-0.5 rounded text-4xs">
            <button
              onClick={() => setInteractionMode("draw")}
              className={`px-2 py-1 rounded transition-colors font-bold uppercase ${
                interactionMode === "draw" ? "bg-cyan-500/10 text-cyan-400" : "text-zinc-500 hover:text-zinc-300"
              }`}
              title="Left Click & Drag to redraw curve freehand"
            >
              1-Finger: Draw
            </button>
            <button
              onClick={() => setInteractionMode("scale")}
              className={`px-2 py-1 rounded transition-colors font-bold uppercase ${
                interactionMode === "scale" ? "bg-purple-500/10 text-purple-400" : "text-zinc-500 hover:text-zinc-300"
              }`}
              title="Pinch with two fingers (or Shift + Drag on Desktop) to scale / stretch curve"
            >
              2-Finger: Stretch
            </button>
            <button
              onClick={() => setInteractionMode("shift")}
              className={`px-2 py-1 rounded transition-colors font-bold uppercase ${
                interactionMode === "shift" ? "bg-emerald-500/10 text-emerald-400" : "text-zinc-500 hover:text-zinc-300"
              }`}
              title="Drag curve vertically to change base energy"
            >
              Shift Base
            </button>
          </div>

          <button
            onClick={() => setShowGraphHelp(!showGraphHelp)}
            className="p-1 border border-zinc-800 rounded text-zinc-500 hover:text-zinc-300 transition-colors cursor-pointer"
            title="Gesture guide"
          >
            <HelpCircle size={14} />
          </button>
        </div>
      </div>

      {showGraphHelp && (
        <div className="bg-zinc-950 border border-zinc-850 p-3 rounded text-3xs text-zinc-400 space-y-1.5 leading-relaxed animate-fade-in">
          <p className="font-bold text-zinc-200">Interactive Gestures Guide:</p>
          <ul className="list-disc pl-4 space-y-0.5">
            <li><strong>Drawing (One Finger / Left Click):</strong> Set selector to <span className="text-cyan-400">Draw</span>. Simply drag horizontally to shape the line.</li>
            <li><strong>Stretching & Scaling (Two Fingers / Shift+Drag):</strong> Set to <span className="text-purple-400">Stretch</span>, or hold <kbd className="text-zinc-300 bg-zinc-800 px-1 rounded">Shift</kbd> while dragging. Drag vertical to scale peak height, drag horizontal to dilate/compress.</li>
            <li><strong>Base Shifting:</strong> Set to <span className="text-emerald-400">Shift Base</span>. Drag vertically to translate the entire energy line up or down.</li>
          </ul>
        </div>
      )}

      {/* Dynamic Energy Graph Canvas Area */}
      <div className="relative" ref={containerRef}>
        <canvas
          ref={canvasRef}
          className="w-full h-[220px] rounded border border-zinc-850 cursor-crosshair"
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUpOrLeave}
          onMouseLeave={handleMouseUpOrLeave}
          onTouchStart={handleTouchStart}
          onTouchMove={handleTouchMove}
          onTouchEnd={handleMouseUpOrLeave}
          onContextMenu={(e) => e.preventDefault()} // prevent right-click context menu
        />
        {/* Dynamic Vibe HUD tag */}
        <div className="absolute bottom-16 left-4 bg-zinc-950/90 border border-zinc-800 px-3 py-1.5 rounded flex items-center gap-2 shadow-lg backdrop-blur-sm animate-fade-in">
          <Zap size={10} className="text-cyan-400 fill-cyan-400 animate-pulse" />
          <div className="flex flex-col">
            <span className="text-5xs text-zinc-500 uppercase tracking-wider font-bold">Dynamic Energy State</span>
            <span className="text-3xs font-black text-cyan-300 font-mono">
              BPM Multiplier: {bpmFactor.toFixed(2)}x | Peak: {Math.max(...adjustedPoints)}%
            </span>
          </div>
        </div>
      </div>

      {/* Vibe & Match Recommendations */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <div className="bg-zinc-950/50 p-3 rounded-lg border border-zinc-850/60 md:col-span-2 space-y-1">
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-cyan-400"></span>
            <span className="text-3xs text-zinc-400 uppercase tracking-widest font-bold">Recommended Mixing Style</span>
            <span className="ml-auto text-4xs bg-zinc-900 border border-zinc-800 text-zinc-300 px-1.5 py-0.5 rounded font-bold font-mono">
              {rec.score}
            </span>
          </div>
          <h4 className="text-2xs font-bold text-zinc-100 uppercase">{rec.vibe}</h4>
          <p className="text-3xs text-zinc-400 leading-normal">{rec.desc}</p>
        </div>

        <div className="bg-zinc-950/50 p-3 rounded-lg border border-zinc-850/60 flex flex-col justify-center text-center space-y-1">
          <span className="text-4xs text-zinc-500 uppercase font-mono">Gesture Vibe Check</span>
          <div className="text-base font-black text-zinc-200">
            {adjustedPoints[activePlayhead] >= 80 ? "🔥 PEAK MODE" : adjustedPoints[activePlayhead] >= 50 ? "🚀 BUILD-UP" : "🍃 CHILL ZONE"}
          </div>
          <span className="text-4xs text-zinc-400 font-mono">
            Active Bar {activePlayhead + 1} Energy: {adjustedPoints[activePlayhead]}%
          </span>
        </div>
      </div>
    </div>
  );
}
