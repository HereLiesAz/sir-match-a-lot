import React, { useState, useEffect } from "react";
import { 
  Music, Plus, Trash2, Search, Sliders, Play, Shuffle, HelpCircle, 
  Sparkles, Check, ChevronRight, AlertCircle, Edit3, Save, X, Download,
  ChevronDown, ChevronUp 
} from "lucide-react";
import { Track, MixMatch, compareTracks, MOCK_DJ_CRATES } from "./types";
import BeatgridTool from "./components/BeatgridTool";

export default function App() {
  // Application State
  const [tracks, setTracks] = useState<Track[]>([]);
  const [selectedCrate, setSelectedCrate] = useState<string>("Tech House & Club Grooves");
  
  // Track inputs & search
  const [searchQuery, setSearchQuery] = useState("");
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [analysisStatus, setAnalysisStatus] = useState("");
  const [analysisError, setAnalysisError] = useState("");

  // Playlist Import State
  const [importTab, setImportTab] = useState<"single" | "playlist">("single");
  const [playlistUrl, setPlaylistUrl] = useState("");
  const [isImportingPlaylist, setIsImportingPlaylist] = useState(false);
  const [playlistError, setPlaylistError] = useState("");
  const [playlistSuccess, setPlaylistSuccess] = useState("");
  const [importStatus, setImportStatus] = useState("");

  // Manual Add Form State
  const [showAddForm, setShowAddForm] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newArtist, setNewArtist] = useState("");
  const [newBpm, setNewBpm] = useState<number>(120);
  const [newKey, setNewKey] = useState("A minor");
  const [newCamelot, setNewCamelot] = useState("8A");
  const [newProgression, setNewProgression] = useState("Am - F - C - G");
  const [newAtmosphere, setNewAtmosphere] = useState("groovy, warm synth, danceable");
  const [newGenres, setNewGenres] = useState("House");
  const [newEnergy, setNewEnergy] = useState<number>(7);

  // Edit Track Modal State
  const [editingTrack, setEditingTrack] = useState<Track | null>(null);

  // Loaded Decks for Beatgrid Alignment Tool
  const [deckATrack, setDeckATrack] = useState<Track | null>(null);
  const [deckBTrack, setDeckBTrack] = useState<Track | null>(null);

  // Expanded states for details on mobile/tablet (helps clean up redundant data until tapped)
  const [expandedTracks, setExpandedTracks] = useState<Record<string, boolean>>({});
  const [expandedPairs, setExpandedPairs] = useState<Record<number, boolean>>({});

  // Active Screen Tab State
  const [activeTab, setActiveTab] = useState<"library" | "decks" | "guide">("library");

  // Analyze song via fullstack Gemini API route
  const analyzeSongDirectly = async (queryText: string) => {
    if (!queryText.trim()) return;

    setIsAnalyzing(true);
    setAnalysisError("");
    setPlaylistError("");
    setPlaylistSuccess("");
    
    // Series of professional, reassuring DJ-focused logs for loading state
    const loadingSteps = [
      "Contacting track catalog archives...",
      "Analyzing transients & groove matrices...",
      "Calculating Camelot harmonic frequencies...",
      "Matching structural note progressions...",
      "Synthesizing atmospheric vibe parameters...",
      "Generating DJ mixing tips..."
    ];

    let currentStepIndex = 0;
    setAnalysisStatus(loadingSteps[0]);

    const statusInterval = setInterval(() => {
      if (currentStepIndex < loadingSteps.length - 1) {
        currentStepIndex++;
        setAnalysisStatus(loadingSteps[currentStepIndex]);
      }
    }, 900);

    try {
      const response = await fetch("/api/analyze", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: queryText })
      });

      const result = await response.json();
      clearInterval(statusInterval);

      if (result.success && result.data) {
        const analyzedTrack: Track = {
          id: `track-${Date.now()}`,
          title: result.data.title,
          artist: result.data.artist,
          bpm: result.data.bpm,
          key: result.data.key,
          camelotKey: result.data.camelotKey,
          progression: result.data.progression,
          atmosphere: result.data.atmosphere,
          genres: result.data.genres || ["Electronic"],
          energyLevel: result.data.energyLevel || 7,
          mixTips: result.data.mixTips,
          isUserAdded: true
        };

        setTracks(prev => [analyzedTrack, ...prev]);
        setSearchQuery("");
        setPlaylistSuccess(`Successfully analyzed & added "${analyzedTrack.title}" directly to Crate!`);
        
        // Autoload to Deck B if Deck A is filled, otherwise Deck A
        if (!deckATrack) {
          setDeckATrack(analyzedTrack);
        } else if (!deckBTrack) {
          setDeckBTrack(analyzedTrack);
        }
      } else {
        setAnalysisError("Could not retrieve track information from Gemini. Please double check the song name.");
      }
    } catch (error) {
      clearInterval(statusInterval);
      setAnalysisError("Network error occurred during music theory analysis. Please try again.");
    } finally {
      setIsAnalyzing(false);
    }
  };

  // Import playlist via fullstack API route
  const importPlaylistDirectly = async (playlistUrlString: string) => {
    if (!playlistUrlString.trim()) return;

    setIsImportingPlaylist(true);
    setPlaylistError("");
    setPlaylistSuccess("");
    setAnalysisError("");
    setImportStatus("Locating playlist content and downloading index...");

    const playlistSteps = [
      "Contacting server metadata archives...",
      "Resolving playlist tracks and authors...",
      "Generating DJ parameters (BPM & Camelot key offsets)...",
      "Analyzing note progressions and structures...",
      "Indexing custom video matches from YouTube Music...",
      "Finalizing crate compilation..."
    ];

    let currentStepIdx = 0;
    const statusInterval = setInterval(() => {
      if (currentStepIdx < playlistSteps.length - 1) {
        currentStepIdx++;
        setImportStatus(playlistSteps[currentStepIdx]);
      }
    }, 1500);

    try {
      const response = await fetch("/api/import-playlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ playlistUrl: playlistUrlString })
      });

      const result = await response.json();
      clearInterval(statusInterval);

      if (result.success && result.tracks && result.tracks.length > 0) {
        // Map the result to our React Track objects
        const importedTracks: Track[] = result.tracks.map((t: any, index: number) => ({
          id: `playlist-${Date.now()}-${index}`,
          title: t.title,
          artist: t.artist,
          bpm: t.bpm,
          key: t.key,
          camelotKey: t.camelotKey,
          progression: t.progression,
          atmosphere: t.atmosphere,
          genres: t.genres || ["Electronic"],
          energyLevel: t.energyLevel || 7,
          mixTips: t.mixTips,
          youtubeId: t.youtubeId || null,
          isUserAdded: true
        }));

        setTracks(prev => [...importedTracks, ...prev]);
        setPlaylistSuccess(`Successfully imported & matched ${importedTracks.length} tracks! Loaded directly into active Crate.`);
        setSearchQuery("");

        // Autoload into active decks if they are currently unpopulated
        if (!deckATrack && importedTracks.length > 0) {
          setDeckATrack(importedTracks[0]);
        }
        if (!deckBTrack && importedTracks.length > 1) {
          setDeckBTrack(importedTracks[1]);
        }
      } else {
        setPlaylistError(result.error || "Failed to retrieve playlist tracks. Ensure the playlist is public.");
      }
    } catch (err) {
      clearInterval(statusInterval);
      setPlaylistError("A server communication error occurred during playlist import.");
    } finally {
      setIsImportingPlaylist(false);
    }
  };

  // Unified submit handler for top search bar
  const handleUnifiedSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const query = searchQuery.trim();
    if (!query) return;

    // Check if it's a link
    const isUrl = query.startsWith("http://") || query.startsWith("https://") || query.includes("spotify.com") || query.includes("youtube.com") || query.includes("youtu.be");

    if (isUrl) {
      if (query.includes("playlist") || query.includes("album") || query.includes("list=") || query.includes("\n") || query.length > 100) {
        importPlaylistDirectly(query);
      } else {
        analyzeSongDirectly(query);
      }
    } else {
      analyzeSongDirectly(query);
    }
  };

  // Load initial crate on mount
  useEffect(() => {
    setTracks(MOCK_DJ_CRATES[selectedCrate]);
    // Pre-load default decks from the crate
    const crateTracks = MOCK_DJ_CRATES[selectedCrate];
    if (crateTracks.length >= 2) {
      setDeckATrack(crateTracks[0]);
      setDeckBTrack(crateTracks[1]);
    }
  }, []);

  // Switch active crates
  const handleCrateChange = (crateName: string) => {
    setSelectedCrate(crateName);
    const crateTracks = MOCK_DJ_CRATES[crateName];
    setTracks(crateTracks);
    setExpandedTracks({});
    setExpandedPairs({});
    if (crateTracks.length >= 2) {
      setDeckATrack(crateTracks[0]);
      setDeckBTrack(crateTracks[1]);
    } else if (crateTracks.length === 1) {
      setDeckATrack(crateTracks[0]);
      setDeckBTrack(null);
    } else {
      setDeckATrack(null);
      setDeckBTrack(null);
    }
  };

  // Export as CSV
  const handleExportCSV = () => {
    if (tracks.length === 0) return;
    
    const headers = ["Title", "Artist", "BPM", "Key", "Camelot Key", "Energy Level", "Genres", "Atmosphere", "Progression", "Mix Tips"];
    
    const rows = tracks.map(track => [
      `"${track.title.replace(/"/g, '""')}"`,
      `"${track.artist.replace(/"/g, '""')}"`,
      track.bpm,
      `"${track.key}"`,
      `"${track.camelotKey}"`,
      track.energyLevel,
      `"${track.genres.join(", ").replace(/"/g, '""')}"`,
      `"${track.atmosphere.replace(/"/g, '""')}"`,
      `"${track.progression.replace(/"/g, '""')}"`,
      `"${track.mixTips.replace(/"/g, '""')}"`
    ]);

    const csvContent = [headers.join(","), ...rows.map(row => row.join(","))].join("\n");
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.setAttribute("href", url);
    
    const fileName = `${selectedCrate.toLowerCase().replace(/[^a-z0-9]/g, "_")}_crate.csv`;
    link.setAttribute("download", fileName);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  // Export as JSON
  const handleExportJSON = () => {
    if (tracks.length === 0) return;
    
    const jsonString = JSON.stringify(tracks, null, 2);
    const blob = new Blob([jsonString], { type: "application/json;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.setAttribute("href", url);
    
    const fileName = `${selectedCrate.toLowerCase().replace(/[^a-z0-9]/g, "_")}_crate.json`;
    link.setAttribute("download", fileName);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  // Legacy single song search handler (replaced by analyzeSongDirectly in unified handler)

  // Add song manually
  const handleManualAdd = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;

    const manualTrack: Track = {
      id: `manual-${Date.now()}`,
      title: newTitle,
      artist: newArtist || "Self Released",
      bpm: Number(newBpm),
      key: newKey,
      camelotKey: newCamelot,
      progression: newProgression,
      atmosphere: newAtmosphere,
      genres: newGenres.split(",").map(g => g.trim()),
      energyLevel: Number(newEnergy),
      mixTips: `Custom Track. Camelot key is ${newCamelot}. Best transition points at standard phrasing intervals.`,
      isUserAdded: true
    };

    setTracks(prev => [manualTrack, ...prev]);
    setShowAddForm(false);
    
    // Clear form
    setNewTitle("");
    setNewArtist("");
    setNewBpm(120);
    setNewKey("A minor");
    setNewCamelot("8A");
    setNewProgression("Am - F - C - G");
    setNewAtmosphere("groovy, house feel");
    setNewGenres("House");
    setNewEnergy(7);

    if (!deckATrack) {
      setDeckATrack(manualTrack);
    } else if (!deckBTrack) {
      setDeckBTrack(manualTrack);
    }
  };

  // Delete track from active crate
  const handleDeleteTrack = (id: string) => {
    setTracks(prev => prev.filter(t => t.id !== id));
    if (deckATrack?.id === id) setDeckATrack(null);
    if (deckBTrack?.id === id) setDeckBTrack(null);
  };

  // Edit track values
  const handleSaveTrackEdit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingTrack) return;

    setTracks(prev => prev.map(t => t.id === editingTrack.id ? editingTrack : t));
    
    // Sync with loaded decks
    if (deckATrack?.id === editingTrack.id) setDeckATrack(editingTrack);
    if (deckBTrack?.id === editingTrack.id) setDeckBTrack(editingTrack);

    setEditingTrack(null);
  };

  // Generate comparisons between all active tracks
  const generateMixPairs = (): MixMatch[] => {
    if (tracks.length < 2) return [];
    
    const pairs: MixMatch[] = [];
    
    for (let i = 0; i < tracks.length; i++) {
      for (let j = i + 1; j < tracks.length; j++) {
        // Compare A to B
        const comparisonAB = compareTracks(tracks[i], tracks[j]);
        pairs.push(comparisonAB);
      }
    }

    // Sort by overall match percentage descending
    // Filter showing matches of 60% or better as requested
    return pairs
      .filter(pair => pair.overallScore >= 60)
      .sort((a, b) => b.overallScore - a.overallScore);
  };

  const mixPairs = generateMixPairs();

  // Real-time library search filtering
  const filteredTracks = tracks.filter(track => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return true;
    // Don't treat URLs as simple local filters
    if (q.startsWith("http") || q.includes(".com") || q.includes("youtube") || q.includes("spotify")) return true;
    return (
      track.title.toLowerCase().includes(q) ||
      track.artist.toLowerCase().includes(q) ||
      track.key.toLowerCase().includes(q) ||
      track.camelotKey.toLowerCase().includes(q) ||
      track.genres.some(genre => genre.toLowerCase().includes(q)) ||
      track.atmosphere.toLowerCase().includes(q)
    );
  });

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 font-sans selection:bg-cyan-500 selection:text-black">
      {/* Visual background ambient element */}
      <div className="absolute top-0 left-1/4 w-96 h-96 bg-cyan-500/5 rounded-full blur-3xl pointer-events-none"></div>
      <div className="absolute top-1/2 right-1/4 w-96 h-96 bg-amber-500/5 rounded-full blur-3xl pointer-events-none"></div>

      {/* Main Container */}
      <div className="max-w-7xl mx-auto px-4 py-8 space-y-8 relative z-10">
        
        {/* Header Branding Area */}
        <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 pb-6 border-b border-zinc-900">
          <div>
            <div className="flex items-center gap-3">
              <div className="p-2 bg-gradient-to-br from-cyan-500 to-teal-500 rounded-lg text-zinc-950 shadow-md shadow-cyan-500/15">
                <Music size={24} className="stroke-[2.5]" />
              </div>
              <div>
                <h1 className="text-xl md:text-2xl font-black tracking-tight uppercase bg-gradient-to-r from-cyan-400 via-teal-300 to-zinc-400 bg-clip-text text-transparent">
                  Sir Match-a-Lot
                </h1>
                <p className="text-2xs text-zinc-400 uppercase tracking-widest font-semibold mt-0.5">
                  The AI Harmonic Analyst & Intelligent DJ Matcher
                </p>
              </div>
            </div>
          </div>

          {/* Tab Screen Navigation */}
          <div className="flex items-center bg-zinc-900 p-1 rounded-xl border border-zinc-850 w-full md:w-auto overflow-x-auto shrink-0">
            <button
              onClick={() => setActiveTab("library")}
              className={`flex-1 md:flex-none px-4 py-2 rounded-lg text-2xs font-extrabold uppercase tracking-wider transition-all duration-200 cursor-pointer flex items-center justify-center gap-1.5 whitespace-nowrap ${
                activeTab === "library"
                  ? "bg-cyan-500 text-zinc-950 shadow-md shadow-cyan-500/10"
                  : "text-zinc-400 hover:text-zinc-200"
              }`}
            >
              <Music size={12} />
              Crate & Matches
            </button>
            <button
              onClick={() => setActiveTab("decks")}
              className={`flex-1 md:flex-none px-4 py-2 rounded-lg text-2xs font-extrabold uppercase tracking-wider transition-all duration-200 cursor-pointer flex items-center justify-center gap-1.5 whitespace-nowrap ${
                activeTab === "decks"
                  ? "bg-cyan-500 text-zinc-950 shadow-md shadow-cyan-500/10"
                  : "text-zinc-400 hover:text-zinc-200"
              }`}
            >
              <Sliders size={12} />
              Training Decks
            </button>
            <button
              onClick={() => setActiveTab("guide")}
              className={`flex-1 md:flex-none px-4 py-2 rounded-lg text-2xs font-extrabold uppercase tracking-wider transition-all duration-200 cursor-pointer flex items-center justify-center gap-1.5 whitespace-nowrap ${
                activeTab === "guide"
                  ? "bg-cyan-500 text-zinc-950 shadow-md shadow-cyan-500/10"
                  : "text-zinc-400 hover:text-zinc-200"
              }`}
            >
              <HelpCircle size={12} />
              Mixing Guide
            </button>
          </div>
        </header>

        {/* Unified Search & Analyzer Bar */}
        <div className="bg-zinc-900 border border-zinc-850 rounded-xl p-5 shadow-xl space-y-4">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div className="space-y-1">
              <h2 className="text-xs font-black text-zinc-400 uppercase tracking-widest flex items-center gap-2">
                <Sparkles size={14} className="text-cyan-400 fill-cyan-400/20" />
                Intelligent Search & Song Analyzer
              </h2>
              <p className="text-3xs text-zinc-400 leading-relaxed">
                Enter a track/musician name to analyze its harmonic features, paste a video link, or paste a Spotify/YouTube playlist URL.
              </p>
            </div>
            
            <button
              onClick={() => setShowAddForm(!showAddForm)}
              className="px-3 py-1.5 bg-zinc-950 hover:bg-zinc-800 border border-zinc-850 rounded-lg text-2xs font-bold text-cyan-400 hover:text-cyan-300 transition-colors flex items-center gap-1.5 self-start md:self-center cursor-pointer"
            >
              {showAddForm ? <X size={12} /> : <Plus size={12} />}
              {showAddForm ? "Cancel Form" : "Add Track Manually"}
            </button>
          </div>

          {!showAddForm ? (
            <div className="space-y-3">
              <form onSubmit={handleUnifiedSubmit} className="flex flex-col sm:flex-row gap-2">
                <div className="relative flex-1">
                  <Search className="absolute left-3 top-3 text-zinc-500" size={16} />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search song title, artist, paste video link or playlist URL..."
                    className="w-full bg-zinc-950 border border-zinc-850 rounded-lg pl-10 pr-4 py-2.5 text-xs text-zinc-200 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    disabled={isAnalyzing || isImportingPlaylist}
                    id="unified-search-input"
                  />
                </div>
                <button
                  type="submit"
                  disabled={isAnalyzing || isImportingPlaylist || !searchQuery.trim()}
                  className="px-6 py-2.5 bg-cyan-500 hover:bg-cyan-400 disabled:bg-zinc-800 disabled:text-zinc-500 text-zinc-950 text-xs font-black uppercase rounded-lg transition-colors cursor-pointer shrink-0"
                  id="unified-analyze-btn"
                >
                  {isAnalyzing ? "Analyzing..." : isImportingPlaylist ? "Importing..." : "Analyze & Match"}
                </button>
              </form>

              {/* Loader Feedback for Analysis / Playlist Import */}
              {(isAnalyzing || isImportingPlaylist) && (
                <div className="bg-zinc-950/60 p-3.5 rounded-lg border border-zinc-800 space-y-2 animate-pulse">
                  <div className="flex items-center justify-between text-2xs">
                    <span className="text-cyan-400 font-bold uppercase tracking-wider">
                      {isAnalyzing ? "Analyzing Harmonic Properties" : "Compiling Playlist Crate"}
                    </span>
                    <span className="text-zinc-500 font-mono text-3xs">Running Gemini AI...</span>
                  </div>
                  <div className="w-full bg-zinc-900 h-1 rounded-full overflow-hidden">
                    <div className="bg-gradient-to-r from-cyan-500 to-teal-500 h-full w-2/3 animate-pulse"></div>
                  </div>
                  <p className="text-3xs text-zinc-400 font-mono italic">{isAnalyzing ? analysisStatus : importStatus}</p>
                </div>
              )}

              {analysisError && (
                <div className="flex items-start gap-2 bg-rose-950/20 border border-rose-900/50 p-3 rounded-lg text-rose-400 text-2xs animate-fade-in">
                  <AlertCircle size={14} className="shrink-0 mt-0.5" />
                  <span>{analysisError}</span>
                </div>
              )}

              {playlistError && (
                <div className="flex items-start gap-2 bg-rose-950/20 border border-rose-900/50 p-3 rounded-lg text-rose-400 text-2xs animate-fade-in">
                  <AlertCircle size={14} className="shrink-0 mt-0.5" />
                  <span>{playlistError}</span>
                </div>
              )}

              {playlistSuccess && (
                <div className="flex items-start gap-2 bg-emerald-950/20 border border-emerald-900/50 p-3 rounded-lg text-emerald-400 text-2xs animate-fade-in">
                  <Check size={14} className="shrink-0 mt-0.5" />
                  <span>{playlistSuccess}</span>
                </div>
              )}

              {/* Quick load presets */}
              <div className="flex flex-wrap items-center gap-1.5 pt-1 text-3xs text-zinc-500 uppercase tracking-wider">
                <span className="font-bold text-zinc-500">Quick Presets:</span>
                {Object.keys(MOCK_DJ_CRATES).map((crateName) => (
                  <button
                    key={crateName}
                    type="button"
                    onClick={() => {
                      handleCrateChange(crateName);
                      setPlaylistSuccess(`Loaded preset crate: ${crateName}`);
                      setPlaylistError("");
                      setAnalysisError("");
                    }}
                    className={`px-2 py-0.5 rounded font-bold text-4xs border transition-all cursor-pointer ${
                      selectedCrate === crateName
                        ? "bg-cyan-500/10 border-cyan-500 text-cyan-400"
                        : "bg-zinc-950 border-zinc-850 hover:bg-zinc-900 text-zinc-400"
                    }`}
                  >
                    {crateName.split(" & ")[0]}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            /* Manual Add Form */
            <form onSubmit={handleManualAdd} className="space-y-4 pt-2 border-t border-zinc-850/50 animate-fade-in">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Title *</label>
                  <input
                    type="text"
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    placeholder="e.g. Midnight Run"
                    required
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-title"
                  />
                </div>
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Artist</label>
                  <input
                    type="text"
                    value={newArtist}
                    onChange={(e) => setNewArtist(e.target.value)}
                    placeholder="e.g. DJ Shadow"
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-artist"
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">BPM *</label>
                  <input
                    type="number"
                    min="40"
                    max="300"
                    value={newBpm}
                    onChange={(e) => setNewBpm(Number(e.target.value))}
                    required
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-bpm"
                  />
                </div>
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Key Name *</label>
                  <input
                    type="text"
                    value={newKey}
                    onChange={(e) => setNewKey(e.target.value)}
                    placeholder="e.g. A minor"
                    required
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-key"
                  />
                </div>
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Camelot Code *</label>
                  <input
                    type="text"
                    value={newCamelot}
                    onChange={(e) => setNewCamelot(e.target.value)}
                    placeholder="e.g. 8A"
                    required
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-camelot"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Genres</label>
                  <input
                    type="text"
                    value={newGenres}
                    onChange={(e) => setNewGenres(e.target.value)}
                    placeholder="House, Electronic"
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-genres"
                  />
                </div>
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Energy (1-10)</label>
                  <input
                    type="number"
                    min="1"
                    max="10"
                    value={newEnergy}
                    onChange={(e) => setNewEnergy(Number(e.target.value))}
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-energy"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Vibe / Atmosphere</label>
                  <input
                    type="text"
                    value={newAtmosphere}
                    onChange={(e) => setNewAtmosphere(e.target.value)}
                    placeholder="e.g. deep sub bass, progressive pads"
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-atmosphere"
                  />
                </div>
                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Note Chord Progression</label>
                  <input
                    type="text"
                    value={newProgression}
                    onChange={(e) => setNewProgression(e.target.value)}
                    placeholder="e.g. Am - F - C - G"
                    className="w-full bg-zinc-950 border border-zinc-850 rounded px-2.5 py-1.5 text-xs text-zinc-300 focus:outline-none focus:ring-1 focus:ring-cyan-500"
                    id="new-progression"
                  />
                </div>
              </div>

              <div className="flex gap-2 justify-end pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddForm(false)}
                  className="px-3 py-1.5 border border-zinc-800 text-zinc-400 hover:text-white rounded-lg text-2xs uppercase tracking-wider font-bold transition-colors cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-3 py-1.5 bg-cyan-500 text-zinc-950 hover:bg-cyan-400 rounded-lg text-2xs uppercase tracking-wider font-extrabold transition-colors cursor-pointer"
                >
                  Save Track
                </button>
              </div>
            </form>
          )}
        </div>

        {/* TAB CONTENTS */}
        {activeTab === "library" && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
            
            {/* LEFT PANEL: Song Library Crate (5 cols) */}
            <section className="lg:col-span-5 space-y-6">
              <div className="bg-zinc-900 border border-zinc-850 rounded-xl p-5 shadow-xl space-y-4 animate-fade-in">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-zinc-850 pb-3">
                  <div className="flex items-center justify-between w-full sm:w-auto gap-2">
                    <div className="flex items-center gap-2">
                      <Sliders size={14} className="text-cyan-400" />
                      <h2 className="text-xs font-bold text-zinc-300 uppercase tracking-widest">
                        Crate Library ({filteredTracks.length})
                      </h2>
                    </div>
                    {filteredTracks.length > 0 && (
                      <button 
                        onClick={() => {
                          const allExpanded = filteredTracks.every(t => expandedTracks[t.id]);
                          const nextState: any = {};
                          if (!allExpanded) {
                            filteredTracks.forEach(t => { nextState[t.id] = true; });
                          }
                          setExpandedTracks(nextState);
                        }}
                        className="text-4xs font-bold text-zinc-500 hover:text-zinc-300 uppercase tracking-widest transition-colors cursor-pointer sm:ml-4 bg-zinc-950 px-2 py-1 rounded border border-zinc-850"
                      >
                        {filteredTracks.every(t => expandedTracks[t.id]) ? "Collapse All" : "Expand All"}
                      </button>
                    )}
                  </div>
                  <p className="text-3xs text-zinc-500 uppercase font-mono font-bold truncate max-w-[150px]">{selectedCrate}</p>
                </div>

                {filteredTracks.length > 0 && (
                  <div className="flex items-center gap-2 bg-zinc-950/40 p-2 rounded-lg border border-zinc-850/60 text-2xs animate-fade-in">
                    <span className="text-3xs uppercase tracking-wider text-zinc-500 font-bold">Export Crate:</span>
                    <button
                      onClick={handleExportCSV}
                      className="flex-1 py-1 px-2 bg-zinc-900 hover:bg-zinc-800 border border-zinc-800 hover:border-zinc-700 rounded text-3xs text-cyan-400 font-bold uppercase tracking-wider transition-all cursor-pointer flex items-center justify-center gap-1"
                      title="Export currently analyzed tracks as a standard CSV spreadsheet"
                      id="export-csv-btn"
                    >
                      <Download size={10} />
                      CSV
                    </button>
                    <button
                      onClick={handleExportJSON}
                      className="flex-1 py-1 px-2 bg-zinc-900 hover:bg-zinc-800 border border-zinc-800 hover:border-zinc-700 rounded text-3xs text-cyan-400 font-bold uppercase tracking-wider transition-all cursor-pointer flex items-center justify-center gap-1"
                      title="Export currently analyzed tracks as raw JSON metadata"
                      id="export-json-btn"
                    >
                      <Download size={10} />
                      JSON
                    </button>
                  </div>
                )}

              {tracks.length === 0 ? (
                <div className="text-center py-12 border border-dashed border-zinc-800 rounded-lg text-zinc-500 space-y-2">
                  <Music className="mx-auto text-zinc-600" size={28} />
                  <p className="text-xs font-medium">Your DJ crate is empty.</p>
                  <p className="text-4xs">Search for a song or load a preset crate above.</p>
                </div>
              ) : (
                <div className="space-y-3 max-h-[440px] overflow-y-auto pr-1">
                  {tracks.map((track) => {
                    const isLoadedA = deckATrack?.id === track.id;
                    const isLoadedB = deckBTrack?.id === track.id;
                    const isExpanded = expandedTracks[track.id] || false;

                    return (
                      <div 
                        key={track.id} 
                        className={`rounded-lg border transition-all flex flex-col justify-between p-3.5 gap-3 ${
                          isLoadedA 
                            ? "bg-cyan-950/15 border-cyan-800/60" 
                            : isLoadedB 
                              ? "bg-amber-950/15 border-amber-800/60" 
                              : "bg-zinc-950 border-zinc-850 hover:border-zinc-800"
                        }`}
                      >
                        {/* Track Info Header (Click to expand/collapse) */}
                        <div 
                          className="flex justify-between items-start gap-2 cursor-pointer select-none group"
                          onClick={() => setExpandedTracks(prev => ({ ...prev, [track.id]: !isExpanded }))}
                          title="Tap to reveal atmosphere details & mixing tips"
                        >
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-1.5">
                              <h3 className="text-xs font-extrabold text-white truncate max-w-[150px] sm:max-w-[190px] group-hover:text-cyan-400 transition-colors">
                                {track.title}
                              </h3>
                              {isExpanded ? (
                                <ChevronUp size={12} className="text-zinc-500 shrink-0" />
                              ) : (
                                <ChevronDown size={12} className="text-zinc-500 shrink-0" />
                              )}
                            </div>
                            <p className="text-3xs text-zinc-400 truncate max-w-[180px] mt-0.5">{track.artist}</p>
                            
                            {/* Genres / tags */}
                            <div className="flex flex-wrap gap-1 mt-2">
                              {track.genres.slice(0, 1).map((g, i) => (
                                <span key={i} className="text-4xs bg-zinc-900 border border-zinc-850 text-zinc-400 px-1.5 py-0.5 rounded">
                                  {g}
                                </span>
                              ))}
                              {track.genres.length > 1 && !isExpanded && (
                                <span className="text-4xs bg-zinc-900 border border-zinc-850 text-zinc-500 px-1 py-0.5 rounded font-mono">
                                  +{track.genres.length - 1}
                                </span>
                              )}
                              {isExpanded && track.genres.slice(1).map((g, i) => (
                                <span key={i} className="text-4xs bg-zinc-900 border border-zinc-850 text-zinc-400 px-1.5 py-0.5 rounded animate-fade-in">
                                  {g}
                                </span>
                              ))}
                              <span className="text-4xs text-zinc-500 self-center">
                                Energy: {track.energyLevel}/10
                              </span>
                            </div>
                          </div>

                          {/* Music Theory Badge */}
                          <div className="text-right shrink-0">
                            <div className="text-2xs font-mono font-black text-zinc-100">{track.bpm} <span className="text-3xs font-medium text-zinc-400">BPM</span></div>
                            <div className="text-3xs font-mono font-bold text-zinc-400 mt-0.5">Key: {track.key}</div>
                            
                            {/* Camelot Badge */}
                            <span className="inline-block bg-zinc-900 border border-zinc-800 text-cyan-400 font-mono text-3xs font-bold px-1.5 py-0.5 rounded mt-1.5">
                              {track.camelotKey}
                            </span>
                          </div>
                        </div>

                        {/* Collapsible Vibe & Mixing Details */}
                        {isExpanded && (
                          <div className="text-4xs text-zinc-400 leading-normal border-t border-zinc-900 pt-2.5 bg-zinc-950/50 p-2.5 rounded animate-fade-in space-y-1.5">
                            <div>
                              <span className="font-extrabold text-zinc-500 uppercase mr-1">Atmosphere:</span>
                              <span className="text-zinc-300">{track.atmosphere}</span>
                            </div>
                            <div>
                              <span className="font-extrabold text-zinc-500 uppercase mr-1">Chord Progression:</span>
                              <code className="font-mono text-zinc-200 bg-zinc-900 px-1 py-0.5 rounded">{track.progression}</code>
                            </div>
                            {track.mixTips && (
                              <div className="border-t border-zinc-900/60 pt-1.5 mt-1">
                                <span className="font-extrabold text-cyan-400 uppercase tracking-wider block mb-0.5">DJ Mixing Tip:</span>
                                <p className="text-zinc-300 italic">"{track.mixTips}"</p>
                              </div>
                            )}
                          </div>
                        )}

                        {/* Action buttons */}
                        <div className="flex justify-between items-center border-t border-zinc-900/60 pt-2">
                          {/* Deck Load Actions */}
                          <div className="flex gap-1.5">
                            <button
                              onClick={() => setDeckATrack(track)}
                              className={`px-2 py-1 rounded text-4xs font-extrabold uppercase tracking-widest cursor-pointer border ${
                                isLoadedA 
                                  ? "bg-cyan-500 text-zinc-950 border-cyan-500" 
                                  : "bg-zinc-900 hover:bg-zinc-850 text-cyan-400 border-zinc-800"
                              }`}
                            >
                              {isLoadedA ? "Loaded A" : "Deck A"}
                            </button>
                            <button
                              onClick={() => setDeckBTrack(track)}
                              className={`px-2 py-1 rounded text-4xs font-extrabold uppercase tracking-widest cursor-pointer border ${
                                isLoadedB 
                                  ? "bg-amber-500 text-zinc-950 border-amber-500" 
                                  : "bg-zinc-900 hover:bg-zinc-850 text-amber-400 border-zinc-800"
                              }`}
                            >
                              {isLoadedB ? "Loaded B" : "Deck B"}
                            </button>
                          </div>

                          {/* Edit / Delete */}
                          <div className="flex gap-1.5">
                            <button
                              onClick={() => setEditingTrack(track)}
                              className="p-1 text-zinc-500 hover:text-zinc-300 transition-colors cursor-pointer"
                              title="Edit track attributes"
                              id={`edit-${track.id}`}
                            >
                              <Edit3 size={12} />
                            </button>
                            <button
                              onClick={() => handleDeleteTrack(track.id)}
                              className="p-1 text-rose-500/80 hover:text-rose-400 transition-colors cursor-pointer"
                              title="Remove track"
                              id={`delete-${track.id}`}
                            >
                              <Trash2 size={12} />
                            </button>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </section>

          {/* RIGHT PANEL: DJ Mix Analysis & Recommendations (7 cols) */}
          <section className="lg:col-span-7 space-y-6">
            
            {/* Live recommended matches list */}
            <div className="bg-zinc-900 border border-zinc-850 rounded-xl p-5 shadow-xl space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-zinc-850">
                <div>
                  <h2 className="text-xs font-bold text-zinc-300 uppercase tracking-widest flex items-center gap-2">
                    <Check size={14} className="text-emerald-400" />
                    Compatible Transitions (Scores ≥60%)
                  </h2>
                  <p className="text-3xs text-zinc-400">DJ pairings analyzed by tempo matches, Camelot wheel harmony, and atmosphere alignment.</p>
                </div>
                <div className="flex items-center justify-between sm:justify-end gap-2.5 w-full sm:w-auto">
                  {mixPairs.length > 0 && (
                    <button 
                      onClick={() => {
                        const allExpanded = mixPairs.every((_, idx) => expandedPairs[idx]);
                        const nextState: any = {};
                        if (!allExpanded) {
                          mixPairs.forEach((_, idx) => { nextState[idx] = true; });
                        }
                        setExpandedPairs(nextState);
                      }}
                      className="text-4xs font-bold text-zinc-500 hover:text-zinc-300 uppercase tracking-widest transition-colors cursor-pointer bg-zinc-950 px-2 py-1 rounded border border-zinc-850"
                    >
                      {mixPairs.every((_, idx) => expandedPairs[idx]) ? "Collapse All" : "Expand All"}
                    </button>
                  )}
                  <span className="bg-zinc-950 border border-zinc-800 text-zinc-400 text-4xs font-mono px-2 py-1 rounded font-black shrink-0">
                    {mixPairs.length} matches
                  </span>
                </div>
              </div>

              {mixPairs.length === 0 ? (
                <div className="text-center py-16 border border-zinc-850 rounded-lg text-zinc-500 space-y-2">
                  <HelpCircle className="mx-auto text-zinc-600" size={32} />
                  <p className="text-xs font-medium">No compatible transitions found.</p>
                  <p className="text-4xs max-w-sm mx-auto">Add at least two songs with similar tempos (or half/double time ratios) and adjacent harmonic keys to display recommendations.</p>
                </div>
              ) : (
                <div className="space-y-4 max-h-[580px] overflow-y-auto pr-1">
                  {mixPairs.map((pair, index) => {
                    const isPerfectHarmonic = pair.keyScore >= 95;
                    const isNudgeCompatible = pair.canMixWithNudge;
                    const isHalfDouble = pair.isHalfTimeDoubleTime;
                    const isExpanded = expandedPairs[index] || false;

                    return (
                      <div 
                        key={index} 
                        className="bg-zinc-950 border border-zinc-850 rounded-xl p-4 space-y-3 hover:border-zinc-800 transition-all animate-fade-in"
                      >
                        {/* Title Match Header bar - Click to toggle expansion */}
                        <div 
                          className="flex justify-between items-center gap-3 cursor-pointer select-none group"
                          onClick={() => setExpandedPairs(prev => ({ ...prev, [index]: !isExpanded }))}
                          title="Tap to reveal detailed analytics & mixing advice"
                        >
                          <div className="flex items-center gap-2 min-w-0">
                            <span className="text-3xs font-mono text-zinc-500 font-bold">#{index + 1}</span>
                            <div className="flex items-center gap-1.5 min-w-0">
                              <span className="text-xs font-black text-zinc-100 group-hover:text-cyan-400 transition-colors truncate max-w-[85px] sm:max-w-[150px] md:max-w-[180px]">{pair.trackA.title}</span>
                              <ChevronRight size={12} className="text-zinc-500 shrink-0" />
                              <span className="text-xs font-black text-zinc-100 group-hover:text-cyan-400 transition-colors truncate max-w-[85px] sm:max-w-[150px] md:max-w-[180px]">{pair.trackB.title}</span>
                            </div>
                            {isExpanded ? (
                              <ChevronUp size={12} className="text-zinc-500 shrink-0" />
                            ) : (
                              <ChevronDown size={12} className="text-zinc-500 shrink-0" />
                            )}
                          </div>

                          {/* Big overall rating badge */}
                          <div className="shrink-0 text-right flex items-center gap-1.5">
                            <span className="hidden sm:inline text-4xs font-mono font-bold text-zinc-500 uppercase tracking-wider">{pair.trackA.camelotKey} ➔ {pair.trackB.camelotKey}</span>
                            <span className={`inline-block font-mono text-xs font-black px-2.5 py-1 rounded-full ${
                              pair.overallScore >= 90 
                                ? "bg-emerald-950 text-emerald-400 border border-emerald-800" 
                                : pair.overallScore >= 75
                                  ? "bg-amber-950 text-amber-400 border border-amber-800"
                                  : "bg-zinc-900 text-zinc-300 border border-zinc-800"
                            }`}>
                              {pair.overallScore}% Match
                            </span>
                          </div>
                        </div>

                        {/* Collapsed view summary indicators */}
                        {!isExpanded && (
                          <div 
                            onClick={() => setExpandedPairs(prev => ({ ...prev, [index]: true }))}
                            className="flex flex-wrap items-center gap-1.5 text-4xs text-zinc-400 bg-zinc-900/30 p-2 rounded cursor-pointer border border-zinc-900/50"
                          >
                            <span className="bg-zinc-950 border border-zinc-850 px-1.5 py-0.5 rounded text-zinc-300 font-mono font-bold">
                              {pair.trackA.bpm} ➔ {pair.trackB.bpm} BPM
                            </span>
                            <span className="bg-zinc-950 border border-zinc-850 px-1.5 py-0.5 rounded text-zinc-300 font-mono font-bold">
                              {pair.trackA.camelotKey} ➔ {pair.trackB.camelotKey}
                            </span>
                            {isPerfectHarmonic && (
                              <span className="bg-emerald-950/20 text-emerald-500 px-1.5 py-0.5 rounded font-extrabold uppercase tracking-wider text-5xs">
                                Perfect Harmonic
                              </span>
                            )}
                            <span className="text-cyan-400 hover:underline font-bold ml-auto cursor-pointer flex items-center gap-0.5">
                              View mixing advice ➔
                            </span>
                          </div>
                        )}

                        {/* Expanded details container */}
                        {isExpanded && (
                          <div className="space-y-3.5 animate-fade-in pt-1 border-t border-zinc-900">
                            {/* Breakdown Metrics */}
                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-center bg-zinc-900/40 p-2 rounded-lg border border-zinc-900">
                              <div>
                                <div className="text-4xs font-bold text-zinc-500 uppercase tracking-widest">Tempo Match</div>
                                <div className="text-xs font-black font-mono mt-0.5 text-zinc-200">{pair.tempoScore}%</div>
                                <div className="w-12 h-1 bg-zinc-850 rounded-full mx-auto mt-1 overflow-hidden">
                                  <div className="h-full bg-cyan-500" style={{ width: `${pair.tempoScore}%` }}></div>
                                </div>
                              </div>

                              <div>
                                <div className="text-4xs font-bold text-zinc-500 uppercase tracking-widest">Key Harmony</div>
                                <div className="text-xs font-black font-mono mt-0.5 text-zinc-200">{pair.keyScore}%</div>
                                <div className="w-12 h-1 bg-zinc-850 rounded-full mx-auto mt-1 overflow-hidden">
                                  <div className="h-full bg-emerald-500" style={{ width: `${pair.keyScore}%` }}></div>
                                </div>
                              </div>

                              <div>
                                <div className="text-4xs font-bold text-zinc-500 uppercase tracking-widest">Progression</div>
                                <div className="text-xs font-black font-mono mt-0.5 text-zinc-200">{pair.progressionScore}%</div>
                                <div className="w-12 h-1 bg-zinc-850 rounded-full mx-auto mt-1 overflow-hidden">
                                  <div className="h-full bg-violet-500" style={{ width: `${pair.progressionScore}%` }}></div>
                                </div>
                              </div>

                              <div>
                                <div className="text-4xs font-bold text-zinc-500 uppercase tracking-widest">Atmosphere</div>
                                <div className="text-xs font-black font-mono mt-0.5 text-zinc-200">{pair.atmosphereScore}%</div>
                                <div className="w-12 h-1 bg-zinc-850 rounded-full mx-auto mt-1 overflow-hidden">
                                  <div className="h-full bg-amber-500" style={{ width: `${pair.atmosphereScore}%` }}></div>
                                </div>
                              </div>
                            </div>

                            {/* Dynamic Relationship Highlights */}
                            <div className="flex flex-wrap gap-1.5">
                              {isPerfectHarmonic && (
                                <span className="text-4xs font-extrabold uppercase tracking-wider bg-emerald-950/40 border border-emerald-800 text-emerald-400 px-2 py-0.5 rounded">
                                  ★ Harmonic Flow ({pair.trackA.camelotKey} ⇄ {pair.trackB.camelotKey})
                                </span>
                              )}
                              
                              {isHalfDouble ? (
                                <span className="text-4xs font-extrabold uppercase tracking-wider bg-purple-950/40 border border-purple-800 text-purple-400 px-2 py-0.5 rounded">
                                  ⇋ Half / Double BPM Sync
                                </span>
                              ) : isNudgeCompatible ? (
                                <span className="text-4xs font-extrabold uppercase tracking-wider bg-cyan-950/40 border border-cyan-800 text-cyan-400 px-2 py-0.5 rounded">
                                  ✓ Pitch Adjustable Match
                                </span>
                              ) : null}

                              <span className="text-4xs font-bold bg-zinc-900 border border-zinc-800 text-zinc-400 px-2 py-0.5 rounded">
                                Energy Match: Δ{Math.abs(pair.trackA.energyLevel - pair.trackB.energyLevel)} levels
                              </span>
                            </div>

                            {/* Actionable advice strings */}
                            <div className="bg-zinc-900/60 p-3 rounded-lg border border-zinc-850/60 space-y-2.5 text-2xs leading-relaxed">
                              <div>
                                <span className="font-extrabold text-cyan-400 uppercase tracking-wider block mb-0.5">Tempo Action Advice:</span>
                                <p className="text-zinc-300">{pair.tempoAdvice}</p>
                              </div>
                              <div>
                                <span className="font-extrabold text-emerald-400 uppercase tracking-wider block mb-0.5">Harmonic Flow Advice:</span>
                                <p className="text-zinc-300">{pair.keyAdvice}</p>
                              </div>
                              <div className="border-t border-zinc-850/80 pt-2 text-3xs text-zinc-400 italic">
                                <span className="font-bold text-zinc-500 not-italic uppercase tracking-widest mr-1.5">Mixing Tip:</span>
                                {pair.trackA.mixTips}
                              </div>
                            </div>

                            {/* Load Mix Direct Action Button */}
                            <button
                              onClick={() => {
                                setDeckATrack(pair.trackA);
                                setDeckBTrack(pair.trackB);
                                // Scroll to training deck smoothly
                                document.getElementById("beatgrid-tool-panel")?.scrollIntoView({ behavior: "smooth" });
                              }}
                              className="flex items-center justify-center gap-2 w-full py-2 bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 hover:border-zinc-700 text-zinc-300 hover:text-white rounded-lg text-2xs font-extrabold uppercase tracking-widest transition-all cursor-pointer"
                              id={`load-mix-${index}`}
                            >
                              <Play size={12} className="fill-current" />
                              Load Pair Into Training Beatgrid Matcher
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </section>
        </div>
        )}
 
        {/* DECKS TAB */}
        {activeTab === "decks" && (
          <div className="space-y-6 animate-fade-in">
            <BeatgridTool 
              tracks={tracks}
              initialDeckATrack={deckATrack}
              initialDeckBTrack={deckBTrack}
            />
          </div>
        )}

        {/* GUIDE TAB */}
        {activeTab === "guide" && (
          <div className="bg-zinc-900 border border-zinc-850 rounded-xl p-6 shadow-xl space-y-6 animate-fade-in">
            <div className="border-b border-zinc-850 pb-4">
              <h2 className="text-sm font-black text-zinc-200 uppercase tracking-widest flex items-center gap-2">
                <Sliders size={16} className="text-cyan-400" />
                The DJ's Harmonic Mixing Quick Guide
              </h2>
              <p className="text-3xs text-zinc-400 uppercase mt-1">Learn to create seamless transitions using the Camelot Wheel</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
              <div className="space-y-4">
                <h3 className="text-xs font-bold text-cyan-400 uppercase tracking-wider">The Golden Harmonic Rules</h3>
                <p className="text-xs text-zinc-300 leading-relaxed">
                  The Camelot system numbers harmonic keys from <strong className="text-zinc-100 font-bold">1 to 12</strong>. Inner rings are <strong className="text-zinc-100 font-bold">A</strong> (Minor keys) and outer rings are <strong className="text-zinc-100 font-bold">B</strong> (Major keys).
                </p>
                
                <ul className="space-y-3.5 text-xs text-zinc-300">
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center bg-cyan-950 text-cyan-400 w-5 h-5 rounded-full text-3xs font-black shrink-0">1</span>
                    <div>
                      <strong className="text-white">Same Key Match (e.g. 8A to 8A):</strong> Perfect alignment. The chords fit together perfectly. Great for long melodic blends.
                    </div>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center bg-cyan-950 text-cyan-400 w-5 h-5 rounded-full text-3xs font-black shrink-0">2</span>
                    <div>
                      <strong className="text-white">Adjacent Keys (e.g. 8A to 9A or 7A):</strong> Step-wise progression. Standard smooth transition. The energy feels cohesive and flowing.
                    </div>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center bg-cyan-950 text-cyan-400 w-5 h-5 rounded-full text-3xs font-black shrink-0">3</span>
                    <div>
                      <strong className="text-white">Relative Major/Minor (e.g. 8A to 8B):</strong> Mood flip. Instantly shifts the atmosphere from deep/moody (Minor) to bright/happy (Major).
                    </div>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center bg-cyan-950 text-cyan-400 w-5 h-5 rounded-full text-3xs font-black shrink-0">4</span>
                    <div>
                      <strong className="text-white">Energy Boost (+2 shift, e.g. 8A to 10A):</strong> Energy boost progression. Forces a perceived dramatic lift in excitement for dancefloor peaks.
                    </div>
                  </li>
                </ul>
              </div>

              <div className="bg-zinc-950 border border-zinc-850 rounded-xl p-5 flex flex-col items-center justify-center text-center space-y-4">
                <span className="text-3xs uppercase text-zinc-400 font-bold tracking-widest">Interactive Camelot Wheel</span>
                
                <div className="relative w-48 h-48 rounded-full border-4 border-dashed border-zinc-800 flex items-center justify-center">
                  <div className="absolute inset-4 rounded-full border-2 border-zinc-850 flex items-center justify-center bg-zinc-900/40">
                    <div className="text-center">
                      <span className="text-2xs font-mono font-black text-cyan-400 block">8A</span>
                      <span className="text-4xs font-mono text-zinc-500">A Minor</span>
                    </div>
                  </div>
                  <span className="absolute top-2 font-mono text-2xs text-zinc-500 font-bold">11A</span>
                  <span className="absolute bottom-2 font-mono text-2xs text-zinc-500 font-bold">5A</span>
                  <span className="absolute right-2 font-mono text-2xs text-zinc-500 font-bold">8B</span>
                  <span className="absolute left-2 font-mono text-2xs text-zinc-500 font-bold">7A</span>
                </div>

                <div className="text-3xs text-zinc-500 uppercase font-mono font-bold">
                  Inner Ring (Minor: A) • Outer Ring (Major: B)
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Floating Edit Track Modal */}
        {editingTrack && (
          <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 z-50 animate-fade-in">
            <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-6 shadow-2xl max-w-md w-full space-y-4">
              <div className="flex justify-between items-center pb-2 border-b border-zinc-800">
                <h3 className="text-sm font-bold text-zinc-100 uppercase tracking-wider">Override Track Theory Values</h3>
                <button 
                  onClick={() => setEditingTrack(null)}
                  className="text-zinc-500 hover:text-zinc-300 transition-colors cursor-pointer"
                >
                  <X size={18} />
                </button>
              </div>

              <form onSubmit={handleSaveTrackEdit} className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Song Title</label>
                    <input
                      type="text"
                      value={editingTrack.title}
                      onChange={(e) => setEditingTrack({ ...editingTrack, title: e.target.value })}
                      required
                      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                    />
                  </div>
                  <div>
                    <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Artist Name</label>
                    <input
                      type="text"
                      value={editingTrack.artist}
                      onChange={(e) => setEditingTrack({ ...editingTrack, artist: e.target.value })}
                      required
                      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">BPM (Tempo)</label>
                    <input
                      type="number"
                      min="40"
                      max="300"
                      value={editingTrack.bpm}
                      onChange={(e) => setEditingTrack({ ...editingTrack, bpm: Number(e.target.value) })}
                      required
                      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                    />
                  </div>
                  <div>
                    <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Key (Standard)</label>
                    <input
                      type="text"
                      value={editingTrack.key}
                      onChange={(e) => setEditingTrack({ ...editingTrack, key: e.target.value })}
                      required
                      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                    />
                  </div>
                  <div>
                    <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Camelot Key</label>
                    <input
                      type="text"
                      value={editingTrack.camelotKey}
                      onChange={(e) => setEditingTrack({ ...editingTrack, camelotKey: e.target.value })}
                      required
                      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Vibes / Atmosphere</label>
                  <textarea
                    value={editingTrack.atmosphere}
                    onChange={(e) => setEditingTrack({ ...editingTrack, atmosphere: e.target.value })}
                    rows={2}
                    className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                  />
                </div>

                <div>
                  <label className="block text-4xs font-bold text-zinc-500 uppercase mb-1">Note Chord Progression</label>
                  <input
                    type="text"
                    value={editingTrack.progression}
                    onChange={(e) => setEditingTrack({ ...editingTrack, progression: e.target.value })}
                    className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-1.5 text-xs text-zinc-200"
                  />
                </div>

                <div className="flex gap-2 justify-end pt-2 border-t border-zinc-800">
                  <button
                    type="button"
                    onClick={() => setEditingTrack(null)}
                    className="px-4 py-2 border border-zinc-800 text-zinc-400 hover:text-white rounded-lg text-2xs uppercase tracking-wider font-bold transition-all cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="px-4 py-2 bg-cyan-500 text-zinc-950 hover:bg-cyan-400 rounded-lg text-2xs uppercase tracking-wider font-extrabold transition-all cursor-pointer"
                    id="save-edit-btn"
                  >
                    Save Changes
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* Footer info branding */}
        <footer className="text-center text-3xs text-zinc-500 uppercase tracking-widest pt-8 border-t border-zinc-900/60">
          DJ Mix Matcher • Powered by Google Gemini & Antigravity Theory • Created in 2026
        </footer>
      </div>
    </div>
  );
}
