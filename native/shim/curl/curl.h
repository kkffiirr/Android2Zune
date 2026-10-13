// Android build stub: libcurl is only used for artist-image proxying, which the
// transfer app does not need. curl_easy_init() returns null so callers fail cleanly.
#pragma once
#include <cstddef>
typedef void CURL; struct curl_slist { char* data; curl_slist* next; };
typedef int CURLcode;
enum { CURLE_OK = 0, CURLE_FAILED_INIT = 2 };
enum { CURLOPT_URL = 1, CURLOPT_WRITEFUNCTION, CURLOPT_WRITEDATA, CURLOPT_HEADERFUNCTION, CURLOPT_HEADERDATA,
       CURLOPT_HTTPHEADER, CURLOPT_FOLLOWLOCATION, CURLOPT_TIMEOUT, CURLOPT_USERAGENT, CURLOPT_NOBODY };
enum { CURLINFO_RESPONSE_CODE = 1 };
inline CURL* curl_easy_init() { return nullptr; }
inline void curl_easy_cleanup(CURL*) {}
inline void curl_easy_reset(CURL*) {}
template <class... A> inline CURLcode curl_easy_setopt(CURL*, int, A...) { return CURLE_FAILED_INIT; }
template <class... A> inline CURLcode curl_easy_getinfo(CURL*, int, A...) { return CURLE_FAILED_INIT; }
inline CURLcode curl_easy_perform(CURL*) { return CURLE_FAILED_INIT; }
inline const char* curl_easy_strerror(CURLcode) { return "curl unavailable on Android build"; }
inline curl_slist* curl_slist_append(curl_slist* l, const char*) { return l; }
inline void curl_slist_free_all(curl_slist*) {}
