// Sprig has no Stretch or Move clip yet (the restless one changed backdrop mid-clip). Until they're made, the
// nearest loop stands in; replacing one is generating its .c file here (tools/make_assets.py, name
// pet_restless or pet_move) and deleting its line below.
#include "../anims.h"

extern "C" {
extern const PetAnim pet_restless = pet_jump;   // stretch: bouncing on the spot
extern const PetAnim pet_move = pet_run;        // move: the run
}
