package app.nichez.niche;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.niche.service.FreshWindow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FreshWindowTests {

    @ParameterizedTest(name = "{0} posts in 24 h -> {1}")
    @CsvSource({
            "0,     the next day",
            "1,     the next day",
            "2,     the next 12 hours",
            "12,    the next 2 hours",
            "13,    the next hour",
            "24,    the next hour",
            "25,    the next 55 minutes",
            "40,    the next 35 minutes",
            "96,    the next 15 minutes",
            "100,   the next 15 minutes",
            "200,   the next 8 minutes",
            "840,   the next 2 minutes",
            "1440,  the next minute",
            "100000, the next minute",
    })
    void wordsTheAverageGapBetweenPosts(long posts, String expected) {
        assertThat(FreshWindow.forPostsInLastDay(posts)).isEqualTo(expected);
    }
}
