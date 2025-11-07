package com.ahhmino.trivia;

import java.util.List;

public class TriviaQuestion {
    private final String question;
    private final List<String> choices; // randomized
    private final int correctIndex;     // index within choices

    public TriviaQuestion(String question, List<String> choices, int correctIndex) {
        this.question = question;
        this.choices = choices;
        this.correctIndex = correctIndex;
    }

    public String question() { return question; }
    public List<String> choices() { return choices; }
    public int correctIndex() { return correctIndex; }
}
