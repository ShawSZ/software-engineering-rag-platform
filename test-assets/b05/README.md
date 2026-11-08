# B05 fixed evaluation set

`evaluation.json` contains 12 original synthetic software engineering texts and 20 fixed questions: 15 answerable and 5 without evidence. The text and questions are dedicated to the public domain under CC0-1.0.

The retrieval test uses this set to check the lexical threshold: 2 points for each body-term match, 1 for each title-term match, minimum `2 * max(1, ceil(0.35 * distinct term count))`. Terms are general Latin words or Chinese character bigrams with common question words removed. There are no question-to-answer rules in production code.

`answerKeywords` are human review cues, not a semantic correctness proof. Real provider evaluation must also inspect the answer against the returned original excerpts. Automated citations only prove that references identify stored source text.
