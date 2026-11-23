cmake_minimum_required(VERSION 3.22.1)
project(kompi_audio LANGUAGES C CXX)

# Separate JNI boundaries; neither library passes C++ objects/STL ownership to another DSO.
add_subdirectory(whistle)
add_subdirectory(aec)
