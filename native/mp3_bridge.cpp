// JNI: MP3 encoding (LAME) for converting FLAC/OGG/WAV/... to a format the Zune plays, and tag copying (TagLib).
// Kotlin decodes with MediaCodec and feeds interleaved 16-bit PCM here; LAME resamples to a legal MP3 rate itself.
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <string>
#include <vector>

#include <lame.h>
#include <taglib/fileref.h>
#include <taglib/tpropertymap.h>
#include <taglib/mpegfile.h>
#include <taglib/id3v2tag.h>
#include <taglib/attachedpictureframe.h>
#include <taglib/flacfile.h>
#include <taglib/flacpicture.h>
#include <taglib/vorbisfile.h>
#include <taglib/opusfile.h>
#include <taglib/xiphcomment.h>

namespace {
struct Enc { lame_t g = nullptr; FILE* f = nullptr; std::vector<unsigned char> buf; };

int outRate(int in) {
    static const int ok[] = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
    for (int r : ok) if (r == in) return in;
    return (in % 48000 == 0) ? 48000 : 44100;     // 96k/192k -> 48k, 88.2k/176.4k and odd rates -> 44.1k
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Picks the front cover (or the first picture) from a list of FLAC-style pictures.
bool pickCover(const TagLib::List<TagLib::FLAC::Picture*>& pics, TagLib::ByteVector& data, TagLib::String& mime) {
    TagLib::FLAC::Picture* best = nullptr;
    for (auto* p : pics) {
        if (!best) best = p;
        if (p->type() == TagLib::FLAC::Picture::FrontCover) { best = p; break; }
    }
    if (!best) return false;
    data = best->data(); mime = best->mimeType();
    return !data.isEmpty();
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kkffiirr_zuneandroid_Native_mp3Open(JNIEnv* env, jclass, jint inRate, jint channels, jint vbrQuality, jstring jpath) {
    auto* e = new Enc();
    std::string path = str(env, jpath);
    e->f = fopen(path.c_str(), "wb+");
    if (!e->f) { delete e; return 0; }
    e->g = lame_init();
    lame_set_in_samplerate(e->g, inRate);
    lame_set_out_samplerate(e->g, outRate(inRate));
    lame_set_num_channels(e->g, channels);
    lame_set_mode(e->g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(e->g, vbr_mtrh);
    lame_set_VBR_q(e->g, vbrQuality);                 // 0 = best (about 245 kbps), 2 = about 190 kbps
    lame_set_write_id3tag_automatic(e->g, 0);         // tags are written afterwards with TagLib (ID3v2.3 + cover)
    if (lame_init_params(e->g) < 0) { lame_close(e->g); fclose(e->f); delete e; return 0; }
    return reinterpret_cast<jlong>(e);
}

// pcm: interleaved 16-bit samples, `frames` samples per channel. Returns 0 on success.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_kkffiirr_zuneandroid_Native_mp3Write(JNIEnv* env, jclass, jlong h, jshortArray pcm, jint frames) {
    auto* e = reinterpret_cast<Enc*>(h);
    if (!e || frames <= 0) return 0;
    size_t need = static_cast<size_t>(1.25 * frames + 7200);
    if (e->buf.size() < need) e->buf.resize(need);
    jshort* s = env->GetShortArrayElements(pcm, nullptr);
    int n = lame_get_num_channels(e->g) == 2
        ? lame_encode_buffer_interleaved(e->g, s, frames, e->buf.data(), static_cast<int>(e->buf.size()))
        : lame_encode_buffer(e->g, s, s, frames, e->buf.data(), static_cast<int>(e->buf.size()));
    env->ReleaseShortArrayElements(pcm, s, JNI_ABORT);
    if (n < 0) return n;
    if (n > 0 && fwrite(e->buf.data(), 1, n, e->f) != static_cast<size_t>(n)) return -100;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kkffiirr_zuneandroid_Native_mp3Close(JNIEnv*, jclass, jlong h) {
    auto* e = reinterpret_cast<Enc*>(h);
    if (!e) return 0;
    unsigned char tail[8192];
    int n = lame_encode_flush(e->g, tail, sizeof tail);
    if (n > 0) fwrite(tail, 1, n, e->f);
    lame_mp3_tags_fid(e->g, e->f);                    // writes the Xing/Info header so players know the duration
    lame_close(e->g);
    int rc = fclose(e->f);
    delete e;
    return rc;
}

// Copies tags (and cover art when the source has one) from any TagLib-readable file into an existing MP3.
// Returns an empty string on success, otherwise a short error message.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kkffiirr_zuneandroid_Native_copyTags(JNIEnv* env, jclass, jstring jsrc, jstring jdst) {
    std::string src = str(env, jsrc), dst = str(env, jdst);
    TagLib::FileRef in(src.c_str());
    if (in.isNull() || !in.file()) return env->NewStringUTF("cannot read tags from the source file");
    TagLib::PropertyMap props = in.file()->properties();
    TagLib::MPEG::File out(dst.c_str());
    if (!out.isValid()) return env->NewStringUTF("cannot open the converted file for tagging");
    out.setProperties(props);

    TagLib::ByteVector cover; TagLib::String mime;
    if (auto* f = dynamic_cast<TagLib::FLAC::File*>(in.file())) pickCover(f->pictureList(), cover, mime);
    else if (auto* v = dynamic_cast<TagLib::Ogg::Vorbis::File*>(in.file())) { if (v->tag()) pickCover(v->tag()->pictureList(), cover, mime); }
    else if (auto* o = dynamic_cast<TagLib::Ogg::Opus::File*>(in.file())) { if (o->tag()) pickCover(o->tag()->pictureList(), cover, mime); }
    if (!cover.isEmpty()) {
        auto* frame = new TagLib::ID3v2::AttachedPictureFrame;
        frame->setMimeType(mime.isEmpty() ? TagLib::String("image/jpeg") : mime);
        frame->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
        frame->setPicture(cover);
        out.ID3v2Tag(true)->addFrame(frame);
    }
    // ID3v2.3: the most widely compatible version (the Zune software itself writes v2.3)
    if (!out.save(TagLib::MPEG::File::ID3v2, TagLib::File::StripOthers, TagLib::ID3v2::v3))
        return env->NewStringUTF("could not save tags");
    return env->NewStringUTF("");
}
