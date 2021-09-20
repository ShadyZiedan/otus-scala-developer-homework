package ru.otus.jdbc.dao.slick



import ru.otus.jdbc.model.{Role, User}
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}




class UserDaoSlickImpl(db: Database)(implicit ec: ExecutionContext) {
  import UserDaoSlickImpl._

  def getUser(userId: UUID): Future[Option[User]] = {
    val res = for {
      user  <- users.filter(user => user.id === userId).result.headOption
      roles <- usersToRoles.filter(_.usersId === userId).map(_.rolesCode).result.map(_.toSet)
    } yield user.map(_.toUser(roles))

    db.run(res)
  }

  def createUser(user: User): Future[User] = {
    val res = for {
      id <- (users returning users.map(_.id)) += UserRow.fromUser(user.copy(id = None))
      newUser <- users.filter(_.id === id).result.headOption
      _ <- usersToRoles ++= user.roles.map(id -> _)
//      roles <- usersToRoles.filter(_.usersId === id).result
    } yield newUser.map(_.toUser(user.roles)).get

    db.run(res.transactionally)
  }

  def updateUser(user: User): Future[Unit] = {
    user.id match {
      case Some(userId) =>
        val updateUser = users
            .filter(_.id === userId)
            .map(u => (u.firstName, u.lastName, u.age))
            .update((user.firstName, user.lastName, user.age))

        val deleteRoles = usersToRoles.filter(_.usersId === userId).delete
        val insertRoles = usersToRoles ++= user.roles.map(userId -> _)

        val action = for {
          updated <- updateUser
          _ <- if (updated <= 0) (users += UserRow.fromUser(user)) else deleteRoles
          _ <- insertRoles
        } yield ()
        db.run(action.transactionally)
      case None => createUser(user).map(_ => ())
    }

  }

  def deleteUser(userId: UUID): Future[Option[User]] = {
    val res = for {
      user <- users.filter(_.id === userId).result.headOption
      roles <- usersToRoles.filter(_.usersId === userId).result
      _ <- usersToRoles.filter(_.usersId === userId).delete
      _ <- users.filter(_.id === userId).delete
    } yield user.map(_.toUser(roles.map(_._2).toSet))
    db.run(res)
  }

  private def findByCondition(condition: Users => Rep[Boolean]): Future[Vector[User]] = {
    val res = for {
      rows <- users.filter(condition).result
      roles <- usersToRoles.filter(_.usersId inSet rows.map(_.id.get)).result.map(_.toSet)
    } yield rows.map(u => u.toUser(roles.collect{ case (uuid, role) if uuid == u.id.get => role}))
    db.run(res.map(_.toVector))
  }

  def findByLastName(lastName: String): Future[Seq[User]] = findByCondition(u => u.lastName === lastName)

  def findAll(): Future[Seq[User]] = findByCondition(_ => true)

  private[slick] def deleteAll(): Future[Unit] = db.run(users.delete.map(_ => ()))
}

object UserDaoSlickImpl {
  implicit val rolesType: BaseColumnType[Role] = MappedColumnType.base[Role, String](
    {
      case Role.Reader => "reader"
      case Role.Manager => "manager"
      case Role.Admin => "admin"
    },
    {
        case "reader"  => Role.Reader
        case "manager" => Role.Manager
        case "admin"   => Role.Admin
    }
  )


  case class UserRow(
      id: Option[UUID],
      firstName: String,
      lastName: String,
      age: Int
  ) {
    def toUser(roles: Set[Role]): User = User(id, firstName, lastName, age, roles)
  }

  object UserRow extends ((Option[UUID], String, String, Int) => UserRow) {
    def fromUser(user: User): UserRow = UserRow(user.id, user.firstName, user.lastName, user.age)
  }

  class Users(tag: Tag) extends Table[UserRow](tag, "users") {
    val id        = column[UUID]("id", O.PrimaryKey, O.AutoInc)
    val firstName = column[String]("first_name")
    val lastName  = column[String]("last_name")
    val age       = column[Int]("age")

    val * = (id.?, firstName, lastName, age).mapTo[UserRow]
  }

  val users: TableQuery[Users] = TableQuery[Users]

  class UsersToRoles(tag: Tag) extends Table[(UUID, Role)](tag, "users_to_roles") {
    val usersId   = column[UUID]("users_id")
    val rolesCode = column[Role]("roles_code")

    val * = (usersId, rolesCode)
  }

  val usersToRoles = TableQuery[UsersToRoles]
}
