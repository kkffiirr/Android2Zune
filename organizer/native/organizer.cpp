// JNI glue for Zune Organizer: list the Zune's library, and edit track/album metadata in place.
// Kotlin passes the UsbDeviceConnection fd and an MTPZ key file; no audio data is transferred.
// Everything for one user action (edit + album edit + re-read) runs in ONE USB session: reconnecting
// on the same fd right after a disconnect hung the Zune for ~90 s and then failed with LIBUSB_ERROR_IO.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <map>
#include <sstream>
#include <streambuf>
#include <string>
#include <vector>
#include "lib/src/ZuneDevice.h"
#include "lib/src/ZuneMtpWriter.h"
#include "lib/src/ZuneMtpReader.h"
#include <mtp/ptp/ObjectPropertyListParser.h>

namespace {
struct LineBuf : std::streambuf {
    JNIEnv* env; jobject cb; jmethodID mid; std::string line;
    LineBuf(JNIEnv* e, jobject c) : env(e), cb(c) { mid = env->GetMethodID(env->GetObjectClass(cb), "onLine", "(Ljava/lang/String;)V"); }
    void flushLine() {
        if (line.empty()) return;
        __android_log_print(ANDROID_LOG_INFO, "ZuneOrganizer", "%s", line.c_str());
        jstring s = env->NewStringUTF(line.c_str()); env->CallVoidMethod(cb, mid, s); env->DeleteLocalRef(s);
        line.clear();
    }
    int overflow(int c) override { if (c == '\n') flushLine(); else if (c != EOF) line += (char)c; return c; }
    ~LineBuf() { flushLine(); }
};

std::string clean(const char* s) {   // tabs/newlines are our delimiters
    std::string r = s ? s : "";
    for (auto& c : r) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    return r;
}

std::vector<std::string> split(const std::string& s, char d) {
    std::vector<std::string> v; std::string cur; std::istringstream is(s);
    while (std::getline(is, cur, d)) v.push_back(cur);
    return v;
}

// Property-list entry writers (same wire format as MtpWriter's private helpers).
void putString(mtp::OutputStream& os, uint32_t h, uint16_t prop, const std::string& v) { os.Write32(h); os.Write16(prop); os.Write16(zune::MtpType::String); os.WriteString(v); }
void putU8(mtp::OutputStream& os, uint32_t h, uint16_t prop, uint8_t v) { os.Write32(h); os.Write16(prop); os.Write16(zune::MtpType::Uint8); os.Write8(v); }
void putU16(mtp::OutputStream& os, uint32_t h, uint16_t prop, uint16_t v) { os.Write32(h); os.Write16(prop); os.Write16(zune::MtpType::Uint16); os.Write16(v); }

// An edit field is "=" (leave alone) or "+value". Only "+" fields are ever written, so a bad value
// in the library listing can never be written back over a field the user did not touch.
bool isSet(const std::string& f) { return !f.empty() && f[0] == '+'; }
std::string val(const std::string& f) { return isSet(f) ? f.substr(1) : ""; }

std::string dateFromYear(int year) { if (year <= 0) return ""; char b[32]; snprintf(b, sizeof b, "%04d0101T000000.0", year); return b; }

// Logs what the device itself now stores for an object, to tell "write ignored" apart from "stored but not yet in the library DB".
void readBack(const std::shared_ptr<mtp::Session>& session, uint32_t h, const std::vector<std::pair<const char*, uint16_t>>& props) {
    std::ostringstream o; o << "  read-back " << h << ":";
    for (const auto& [label, prop] : props) {
        try { o << " " << label << "='" << session->GetObjectStringProperty(mtp::ObjectId(h), mtp::ObjectProperty(prop)) << "'"; }
        catch (const std::exception& e) { o << " " << label << "=<" << e.what() << ">"; }
    }
    std::cout << o.str() << std::endl;
}

// lines: id \t title \t artist \t genre \t track \t year   (each "=" or "+value")
std::string editTracks(const std::shared_ptr<mtp::Session>& session, const std::string& arg) {
    int ok = 0, bad = 0;
    for (const auto& line : split(arg, '\n')) {
        if (line.empty()) continue;
        auto f = split(line, '\t');
        while (f.size() < 6) f.push_back("=");
        uint32_t h = (uint32_t)atoll(f[0].c_str());
        try {
            mtp::ByteArray list; mtp::OutputStream os(list);
            uint32_t n = 4;   // constants the upload path always sends
            for (int i = 1; i <= 5; i++) if (isSet(f[i])) n++;
            os.Write32(n);
            putU16(os, h, zune::MtpProp::MetaGenre, 1);
            putU8(os, h, zune::MtpProp::ZuneCollectionId, 0);
            putU8(os, h, zune::MtpProp::ZunePropDAB2, 0);
            putU16(os, h, zune::MtpProp::DC9D, 0);
            if (isSet(f[1])) putString(os, h, zune::MtpProp::Name, val(f[1]));
            if (isSet(f[2])) putString(os, h, zune::MtpProp::Artist, val(f[2]));
            if (isSet(f[3])) putString(os, h, zune::MtpProp::Genre, val(f[3]));
            if (isSet(f[4])) putU16(os, h, zune::MtpProp::Track, (uint16_t)atoi(val(f[4]).c_str()));
            if (isSet(f[5])) putString(os, h, zune::MtpProp::DateAuthored, dateFromYear(atoi(val(f[5]).c_str())));
            std::cout << "Track " << h << ": set";
            if (isSet(f[1])) std::cout << " title='" << val(f[1]) << "'";
            if (isSet(f[2])) std::cout << " artist='" << val(f[2]) << "'";
            if (isSet(f[3])) std::cout << " genre='" << val(f[3]) << "'";
            if (isSet(f[4])) std::cout << " track=" << val(f[4]);
            if (isSet(f[5])) std::cout << " year=" << val(f[5]);
            std::cout << std::endl;
            session->SetObjectPropList(list);
            ok++;
            readBack(session, h, {{"title", zune::MtpProp::Name}, {"artist", zune::MtpProp::Artist}, {"albumArtist", zune::MtpProp::AlbumArtist},
                                  {"genre", zune::MtpProp::Genre}});
        } catch (const std::exception& e) { std::cout << "FAILED track " << h << ": " << e.what() << std::endl; bad++; }
    }
    return std::to_string(ok) + " songs written, " + std::to_string(bad) + " failed";
}

// lines: albumObjectId \t name \t artist   (each "=" or "+value"). Classic only: no artist objects.
std::string editAlbums(const std::shared_ptr<mtp::Session>& session, const std::string& arg) {
    int ok = 0, bad = 0;
    for (const auto& line : split(arg, '\n')) {
        if (line.empty()) continue;
        auto f = split(line, '\t');
        while (f.size() < 3) f.push_back("=");
        uint32_t h = (uint32_t)atoll(f[0].c_str());
        try {
            mtp::ByteArray list; mtp::OutputStream os(list);
            uint32_t n = 1;
            if (isSet(f[1])) n++;
            if (isSet(f[2])) n++;
            os.Write32(n);
            putU8(os, h, zune::MtpProp::ZuneCollectionId, 0);
            if (isSet(f[2])) putString(os, h, zune::MtpProp::Artist, val(f[2]));
            if (isSet(f[1])) putString(os, h, zune::MtpProp::Name, val(f[1]));
            std::cout << "Album " << h << ": set";
            if (isSet(f[1])) std::cout << " name='" << val(f[1]) << "'";
            if (isSet(f[2])) std::cout << " artist='" << val(f[2]) << "'";
            std::cout << std::endl;
            session->SetObjectPropList(list);
            ok++;
            readBack(session, h, {{"name", zune::MtpProp::Name}, {"artist", zune::MtpProp::Artist}});
        } catch (const std::exception& e) { std::cout << "FAILED album " << h << ": " << e.what() << std::endl; bad++; }
    }
    return std::to_string(ok) + " albums written, " + std::to_string(bad) + " failed";
}

// Puts songs that have no album into one. lines: existingAlbumObjectId(0 = create) \t albumArtist \t albumName \t id,id,...
// Same steps the upload path uses for a new album: create the album object in the Albums folder, then set its list of
// tracks (always the full list: existing members + the new ones). Also stamps the album name on each track, best effort.
std::string linkAlbums(ZuneDevice& dev, const std::shared_ptr<mtp::Session>& session, const std::string& arg) {
    int ok = 0, bad = 0; uint32_t albumsFolder = 0;
    for (const auto& line : split(arg, '\n')) {
        if (line.empty()) continue;
        auto f = split(line, '\t');
        while (f.size() < 4) f.push_back("");
        try {
            uint32_t obj = (uint32_t)atoll(f[0].c_str());
            std::vector<uint32_t> ids;
            if (obj) for (const auto& h : session->GetObjectReferences(mtp::ObjectId(obj)).ObjectHandles) ids.push_back(h.Id);
            if (!obj) {
                if (!albumsFolder) {
                    albumsFolder = zune::MtpWriter::DiscoverRoot(session, dev.GetDefaultStorageId(), false).albums_folder;
                    if (!albumsFolder) throw std::runtime_error("the Albums folder was not found on the Zune");
                }
                zune::AlbumProperties ap; ap.artist = f[1]; ap.album_name = f[2]; ap.is_hd = false;
                obj = zune::MtpWriter::CreateAlbumMetadata(session, dev.GetDefaultStorageId(), albumsFolder, ap);
                if (!obj) throw std::runtime_error("the Zune did not create the album");
                std::cout << "Created album " << obj << ": '" << f[2] << "' by '" << f[1] << "'" << std::endl;
            } else std::cout << "Adding to existing album " << obj << ": '" << f[2] << "' by '" << f[1] << "'" << std::endl;
            std::vector<uint32_t> added;
            for (const auto& s : split(f[3], ',')) {
                uint32_t id = (uint32_t)atoll(s.c_str());
                if (id && std::find(ids.begin(), ids.end(), id) == ids.end()) { ids.push_back(id); added.push_back(id); }
            }
            zune::MtpWriter::SetAlbumReferences(session, obj, ids.data(), ids.size());
            std::cout << "Album " << obj << " now lists " << ids.size() << " songs (" << added.size() << " added)" << std::endl;
            for (uint32_t id : added) {   // keep the song's own tags consistent with the album it now belongs to
                try {
                    mtp::ByteArray list; mtp::OutputStream os(list);
                    os.Write32(2);
                    putString(os, id, zune::MtpProp::AlbumName, f[2]);
                    putString(os, id, zune::MtpProp::AlbumArtist, f[1]);
                    session->SetObjectPropList(list);
                } catch (const std::exception& e) { std::cout << "  song " << id << ": album name tag not written (" << e.what() << ")" << std::endl; }
            }
            readBack(session, obj, {{"name", zune::MtpProp::Name}, {"artist", zune::MtpProp::Artist}});
            auto back = session->GetObjectReferences(mtp::ObjectId(obj)).ObjectHandles.size();
            std::cout << "  read-back " << obj << ": " << back << " songs linked" << std::endl;
            ok++;
        } catch (const std::exception& e) { std::cout << "FAILED adding to album '" << f[2] << "': " << e.what() << std::endl; bad++; }
    }
    return std::to_string(ok) + " albums filled, " + std::to_string(bad) + " failed";
}

std::string listing(ZuneDevice& dev, bool hd, const std::shared_ptr<mtp::Session>& session) {
    ZuneMusicLibrary* lib = dev.GetMusicLibrary();
    if (!lib) return "ERR library read failed";
    std::cout << "Library counts: tracks=" << lib->track_count << " albums=" << lib->album_count << " artists=" << lib->artist_count
              << " genres=" << lib->genre_count << " artworks=" << lib->artwork_count << " playlists=" << lib->playlist_count << std::endl;
    std::map<uint32_t, const ZuneMusicAlbum*> albums;
    for (uint32_t i = 0; i < lib->album_count; i++) albums[lib->albums[i].atom_id] = &lib->albums[i];
    // the album's MTP object is the .alb file: alb_reference -> object id (same table the artwork uses)
    std::map<std::string, uint32_t> albObj;
    for (uint32_t i = 0; i < lib->artwork_count; i++)
        if (lib->artworks[i].alb_reference) albObj[lib->artworks[i].alb_reference] = lib->artworks[i].mtp_object_id;
    auto objOf = [&](const ZuneMusicAlbum* a) -> uint32_t {
        if (!a || !a->alb_reference) return 0;
        auto it = albObj.find(a->alb_reference); return it == albObj.end() ? 0 : it->second;
    };
    // The library database links each song to an artist record and skips records the Zune creates after an edit, so a
    // freshly edited artist showed as blank. The objects' own properties are what the Zune itself displays: prefer those.
    std::map<uint32_t, std::string> mtpArtist, mtpGenre;
    // The Zune rejects a whole-library property request (InvalidObjectPropCode), so ask per song, and only for songs whose
    // database artist/genre looks missing or odd (blank or a {GUID}); capped, and stops after repeated failures so a
    // misbehaving device can't stall the read.
    auto odd = [](const char* s) { return !s || !*s || *s == '{'; };
    int asked = 0, failures = 0;
    for (uint32_t i = 0; i < lib->track_count && asked < 300 && failures < 3; i++) {
        const auto& t = lib->tracks[i];
        if (!odd(t.artist_name) && !odd(t.genre)) continue;
        asked++;
        try {
            mtpArtist[t.atom_id] = session->GetObjectStringProperty(mtp::ObjectId(t.atom_id), mtp::ObjectProperty(zune::MtpProp::Artist));
            mtpGenre[t.atom_id] = session->GetObjectStringProperty(mtp::ObjectId(t.atom_id), mtp::ObjectProperty(zune::MtpProp::Genre));
            failures = 0;
        } catch (const std::exception& e) { failures++; std::cout << "Device property read failed for " << t.atom_id << ": " << e.what() << std::endl; }
    }
    std::cout << "Asked the device about " << asked << " songs with a blank or odd artist/genre" << std::endl;
    auto pick = [](const std::map<uint32_t, std::string>& m, uint32_t id, const char* dbValue) -> std::string {
        auto it = m.find(id);
        return clean(it != m.end() && !it->second.empty() ? it->second.c_str() : dbValue);
    };
    int overridden = 0;
    std::ostringstream o;
    o << "OK\nD\t" << clean(dev.GetName().c_str()) << "\t" << (hd ? 1 : 0) << "\n";
    for (uint32_t i = 0; i < lib->track_count; i++) {
        const auto& t = lib->tracks[i];
        auto a = albums.find(t.album_ref);
        std::string artist = pick(mtpArtist, t.atom_id, t.artist_name), genre = pick(mtpGenre, t.atom_id, t.genre);
        if (artist != clean(t.artist_name) || genre != clean(t.genre)) overridden++;
        o << "T\t" << t.atom_id << "\t" << clean(t.title) << "\t" << artist << "\t"
          << (a != albums.end() ? clean(a->second->title) : "") << "\t" << genre << "\t"
          << t.track_number << "\t" << t.disc_number << "\t" << t.duration_ms << "\t"
          << (a != albums.end() ? a->second->release_year : 0) << "\t" << t.playcount << "\t"
          << (a != albums.end() ? objOf(a->second) : 0) << "\t" << clean(t.artist_guid) << "\n";
    }
    for (const auto& [id, a] : albums)
        o << "A\t" << objOf(a) << "\t" << clean(a->title) << "\t" << pick(mtpArtist, objOf(a), a->artist_name) << "\t" << a->release_year << "\n";
    std::cout << "Songs whose artist/genre differ between the device and its database: " << overridden << std::endl;
    zune::MtpReader::FreeLibrary(lib);
    return o.str();
}

// cmd "list": read the library.  cmd "apply": arg = "TRACKS\n<lines>\nALBUMS\n<lines>", written, then the library is re-read
// in the same session so the result shows what the Zune now reports.  Reply: "OK\n" + payload, with an "R\t<summary>" line.
std::string run(int fd, const std::string& mtpz, const std::string& cmd, const std::string& arg) {
    ZuneDevice dev;
    dev.SetLogCallback([](const std::string& m) { std::cout << "  [device] " << m << std::endl; });
    if (!mtpz.empty()) dev.SetMtpzDataPath(mtpz);
    if (!dev.ConnectUSB(fd)) return "ERR connect failed";
    auto session = dev.GetMtpSession();
    if (!session) return "ERR no MTP session";
    bool hd = dev.GetDeviceFamily() == zune::DeviceFamily::Pavo;
    std::cout << "Connected to " << dev.GetName() << (hd ? " [HD]" : " [Classic]") << std::endl;
    try { session->Operation9217(1); } catch (const std::exception& e) { std::cout << "SyncDeviceDB failed: " << e.what() << std::endl; }

    std::string summary;
    if (cmd == "apply") {
        // arg = "TRACKS\n<lines>\nALBUMS\n<lines>\nLINKS\n<lines>"
        auto part = [&](const std::string& begin, const std::string& end) {
            size_t b = arg.find(begin); if (b == std::string::npos) return std::string();
            b += begin.size();
            size_t e = end.empty() ? std::string::npos : arg.find(end, b);
            return arg.substr(b, e == std::string::npos ? std::string::npos : e - b);
        };
        std::string tracksPart = part("TRACKS\n", "\nALBUMS\n"), albumsPart = part("\nALBUMS\n", "\nLINKS\n"), linksPart = part("\nLINKS\n", "");
        if (!tracksPart.empty()) summary += editTracks(session, tracksPart) + "; ";
        if (!albumsPart.empty()) {
            if (hd) summary += "album edits not supported on Zune HD yet; ";
            else summary += editAlbums(session, albumsPart) + "; ";
        }
        if (!linksPart.empty()) {
            if (hd) summary += "adding songs to albums is not supported on Zune HD yet; ";
            else summary += linkAlbums(dev, session, linksPart) + "; ";
        }
        try { session->Operation9215(); } catch (const std::exception& e) { std::cout << "Finalize failed: " << e.what() << std::endl; }
    } else if (cmd != "list") return "ERR unknown command";

    // diagnostic: save the raw metadata database exactly as the Zune sends it (arg = output path), to compare with what the parser shows
    if (cmd == "list" && !arg.empty()) {
        try {
            mtp::ByteArray raw = zune::MtpReader::ReadZuneMetadata(session, {0x03, 0x92, 0x1f});
            FILE* f = fopen(arg.c_str(), "wb");
            if (f) { fwrite(raw.data(), 1, raw.size(), f); fclose(f); }
            std::cout << "Saved raw library database: " << raw.size() << " bytes" << std::endl;
        } catch (const std::exception& e) { std::cout << "Raw database save failed: " << e.what() << std::endl; }
    }

    std::string out = listing(dev, hd, session);
    if (out.rfind("OK\n", 0) == 0 && !summary.empty()) out.insert(3, "R\t" + summary + "\n");
    return out;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kkffiirr_zuneorganizer_Native_run(JNIEnv* env, jclass, jint fd, jstring mtpz, jstring cmd, jstring arg, jobject cb) {
    auto str = [&](jstring j) { const char* c = env->GetStringUTFChars(j, nullptr); std::string s = c; env->ReleaseStringUTFChars(j, c); return s; };
    std::string m = str(mtpz), c = str(cmd), a = str(arg), out;
    LineBuf buf(env, cb);
    auto *oldOut = std::cout.rdbuf(&buf), *oldErr = std::cerr.rdbuf(&buf);
    // Library errors are C++ exceptions; letting one escape through JNI aborts the app.
    try { out = run(fd, m, c, a); }
    catch (const std::exception& e) { out = std::string("ERR ") + e.what(); }
    catch (...) { out = "ERR unknown exception"; }
    std::cout.flush(); std::cout.rdbuf(oldOut); std::cerr.rdbuf(oldErr);
    return env->NewStringUTF(out.c_str());
}
