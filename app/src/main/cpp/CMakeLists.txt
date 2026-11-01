cmake_minimum_required(VERSION 3.22)
project(tvaudio C CXX)
add_library(tvcodec SHARED alac_bridge.cpp apple-alac/ALACDecoder.cpp apple-alac/ALACBitUtilities.c
    apple-alac/EndianPortable.c apple-alac/ag_dec.c apple-alac/dp_dec.c apple-alac/matrix_dec.c)
target_include_directories(tvcodec PRIVATE apple-alac)
target_compile_features(tvcodec PRIVATE cxx_std_17)
if(NOT ANDROID)
set(SHAIRPLAY ${CMAKE_CURRENT_SOURCE_DIR}/shairplay)
set(LIB ${SHAIRPLAY}/lib)
set(SOURCES base64.c digest.c http_parser.c http_request.c http_response.c httpd.c
    logger.c netutils.c raop.c raop_buffer.c raop_rtp.c rsakey.c rsapem.c sdp.c
    aes_ctr.c pairing.c utils.c fairplay_dummy.c plist.c)
list(TRANSFORM SOURCES PREPEND "${LIB}/")
file(GLOB CRYPTO ${LIB}/crypto/*.c ${LIB}/alac/*.c ${LIB}/ed25519/*.c)
# Keep LGPL code in a separate, replaceable shared library.
add_library(shairplay SHARED ${SOURCES} ${CRYPTO} ${LIB}/curve25519/curve25519-donna.c)
target_include_directories(shairplay PUBLIC ${SHAIRPLAY}/include/shairplay PRIVATE ${LIB})
target_link_libraries(shairplay m)
target_link_libraries(shairplay pthread)
endif()
