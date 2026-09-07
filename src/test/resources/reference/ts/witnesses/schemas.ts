import { z } from "zod";

/**
 * Create payload for a witness/declarant (deedpoll_witness). All four fields are required in
 * the source, and the lengths mirror the source columns. `declarant` flags a declarant as
 * opposed to an ordinary witness.
 */
export const createWitnessSchema = z.object({
  witnessName: z.string().max(100),
  witnessAddress: z.string().max(250),
  witnessOccupation: z.string().max(100),
  declarant: z.boolean()
});

/** Update payload — PATCH semantics, so every field becomes optional. */
export const updateWitnessSchema = createWitnessSchema.partial();

export type CreateWitnessInput = z.infer<typeof createWitnessSchema>;
export type UpdateWitnessInput = z.infer<typeof updateWitnessSchema>;
