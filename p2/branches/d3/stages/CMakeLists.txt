ADD_LIBRARY(p2stages stageLoader.C tokenizer.C CSVstage.C XTraceStage.C CSVtail.C XTraceTail.C GangliaTail.C)

TARGET_LINK_LIBRARIES(p2stages p2core)

INCLUDE_DIRECTORIES(${CMAKE_CURRENT_SOURCE_DIR})
