-- A Parivar's activeness stops at what its next level asks for (it was running past it, e.g. 11,42,082 / 7,60,000).
UPDATE parivars p SET exp = l.exp_needed FROM parivar_levels l WHERE l.level = p.level + 1 AND p.exp > l.exp_needed;
