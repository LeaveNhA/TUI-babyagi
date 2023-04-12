# BabyAGI in ClojureScript

Revolutionize task management with this AI-powered, Lisp-based system.

BabyAGI is a powerful task management system implemented in ClojureScript, a modern Lisp dialect. By leveraging Lisp's strengths in symbolic computation, knowledge representation, and functional programming, BabyAGI offers a sophisticated and adaptable solution for managing tasks autonomously.

## Features

- **Intuitive TUI**: Utilizing react-blessed for an all-in-one page terminal user interface.
- **Immutable Process**: Pause, play, and even revert the task management system to a previous state.
- **Dynamic Monitoring**: Keep track of statistics and monitor system performance in real-time.
- **API Integration**: Share collected task results through a dedicated API for seamless data exchange.

## Usage

Detailed instructions on how to set up and use BabyAGI will be provided here in the future. Stay tuned for updates!

You can simply clone the project and run `npm install` and `npm run build`. It generates the final output (program) as a single JS file in lib folder. You can run it by `node lib/main.js`.

Do not forget that the application needs OpenAI and Pinecone API keys. You should get them by their services' websites and inject them by: `PINECONE_API_KEY="KEY" OPENAI_API_KEY="KEY" node lib/main.js`.

## Roadmap

- Improve information collection and processing capabilities.
- Enhance real-time monitoring of system statistics.
- Implement API for sharing collected task results.
- Refine the design of BabyAGI's immutable process for better user control.

## Credits

The original idea for [BabyAGI](https://github.com/yoheinakajima/babyagi) was conceived by @yoheinakajima. This ClojureScript implementation is a tribute to the incredible Lisp community and aims to contribute to its ongoing success.

---

Join us in revolutionizing task management with BabyAGI in ClojureScript!
