// Crumb has no clips for these yet. Until they're made, the nearest loop stands in; replacing one is just
// generating its .c file here (tools/make_assets.py, name pet_restless or pet_move) and deleting its line below.
#include "../anims.h"

extern "C" {
extern const PetAnim pet_restless = pet_jump;   // stretch: hopping about
extern const PetAnim pet_move = pet_run;        // move: the walk
}
