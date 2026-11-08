package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonObject;

/**
 * One question to the strategist.
 *
 * @param sistema the fixed part (rules, plans, schema: cached by the provider where supported)
 * @param utente the variable part (fantasy level + strategic state of the round)
 * @param schema JSON schema of the answer (structured output where the provider has it)
 * @param fantasia 1 (orthodox) - 5 (surprising), also the temperature where accepted
 */
public record Richiesta(String sistema, String utente, JsonObject schema, int fantasia) {}
