[View code on GitHub](https://github.com/alephium/alephium/blob/master/util/src/main/scala/org/alephium/util/BaseActor.scala)

This file contains the implementation of a `BaseActor` trait that provides some common functionality to all actors in the Alephium project. The `BaseActor` trait extends the `Actor` trait and mixes in the `ActorLogging` trait to provide logging capabilities. 

The `BaseActor` trait defines several methods that can be used by actors to schedule messages to be sent to themselves or other actors. These methods include `scheduleCancellable`, `schedule`, `scheduleCancellableOnce`, and `scheduleOnce`. The `scheduleCancellable` and `scheduleCancellableOnce` methods return a `Cancellable` object that can be used to cancel the scheduled message. The `schedule` and `scheduleOnce` methods return `Unit` and do not provide a way to cancel the scheduled message.

The `BaseActor` trait also defines a `terminateSystem` method that can be used to terminate the actor system. The behavior of this method depends on the current environment. In the production environment, the method calls `sys.exit(1)` to terminate the system. In the integration environment, the method calls `context.system.terminate()` to terminate the system. In all other environments, the method calls `context.stop(self)` to stop the actor.

The `BaseActor` trait overrides the `unhandled` method to log a warning message when an actor receives an unhandled message. The trait also overrides the `supervisorStrategy` method to provide a default strategy for handling exceptions. The default strategy is an instance of the `DefaultStrategy` class, which is defined in this file. The `DefaultStrategy` class extends the `SupervisorStrategyConfigurator` trait and provides two strategies: a `resumeStrategy` and a `stopStrategy`. The `resumeStrategy` resumes the actor when an exception is thrown, while the `stopStrategy` stops the actor when an exception is thrown. The strategy used depends on the current environment. In the test environment, the `stopStrategy` is used, while in all other environments, the `resumeStrategy` is used.

Overall, this file provides a set of common functionality that can be used by actors in the Alephium project. The scheduling methods can be used to schedule messages to be sent to actors at a later time, while the `terminateSystem` method can be used to terminate the actor system. The `unhandled` method provides a way to log unhandled messages, while the `supervisorStrategy` method provides a default strategy for handling exceptions.
## Questions: 
 1. What is the purpose of the `BaseActor` trait?
- The `BaseActor` trait is used to define common functionality for actors in the Alephium project, such as scheduling messages and handling unhandled messages.

2. What is the purpose of the `scheduleCancellable` and `scheduleCancellableOnce` methods?
- The `scheduleCancellable` and `scheduleCancellableOnce` methods are used to schedule messages to be sent to an actor with a specified delay and optionally a fixed delay. The `Cancellable` returned can be used to cancel the scheduled message.

3. What is the purpose of the `DefaultStrategy` class?
- The `DefaultStrategy` class is used to define the supervisor strategy for actors in the Alephium project. In this case, it defines two strategies: one to resume the actor on an unhandled throwable and one to stop the actor on an unhandled throwable, depending on the current environment.